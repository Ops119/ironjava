package dev.ironjava.orchestrator;

import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.config.PinnedVersions;
import dev.ironjava.domain.Domain.ReasonCode;
import dev.ironjava.domain.Domain.ReasonMessages;
import dev.ironjava.domain.Domain.Role;
import dev.ironjava.domain.Domain.RunKind;
import dev.ironjava.domain.Domain.RunState;
import dev.ironjava.domain.Domain.Transitions;
import dev.ironjava.gateway.PublicationNotConfiguredException;
import dev.ironjava.gateway.PublishReconcileException;
import dev.ironjava.gateway.PublishRequest;
import dev.ironjava.gateway.RepositoryAccessException;
import dev.ironjava.gateway.RepositoryGateway;
import dev.ironjava.gateway.RoutingRepositoryGateway;
import dev.ironjava.gateway.SourceSnapshot;
import dev.ironjava.gateway.StaleBaseException;
import dev.ironjava.gateway.Tree;
import dev.ironjava.json.JsonCodec;
import dev.ironjava.patch.PatchRejectedException;
import dev.ironjava.patch.PomPatchService;
import dev.ironjava.persistence.FindingEntity;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.RemediationRunEntity;
import dev.ironjava.persistence.ScannerReportEntity;
import dev.ironjava.persistence.ScannerReportRepository;
import dev.ironjava.policy.PomInspector;
import dev.ironjava.recommendation.RecommendationClient;
import dev.ironjava.recommendation.RecommendationValidator;
import dev.ironjava.scan.OsvReportParser;
import dev.ironjava.scan.ScanOutcomeInterpreter;
import dev.ironjava.scan.ScannerOutcome;
import dev.ironjava.verification.CommandProfiles;
import dev.ironjava.verification.CommandRunner;
import dev.ironjava.verification.ExtensionGuard;
import dev.ironjava.verification.MavenOutputParser;
import dev.ironjava.verification.PublicationGateEvaluator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RemediationPipeline {
    private final IronJavaProperties properties;
    private final RemediationRepository remediations;
    private final FindingRepository findings;
    private final ScannerReportRepository reports;
    private final RepositoryCatalog catalog;
    private final RepositoryGateway gateway;
    private final CommandRunner runner;
    private final RecommendationClient recommendations;
    private final AuditService audit;
    private final QueueService queue;
    private final WorkspaceManager workspaces;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();
    private final PomPatchService patcher = new PomPatchService();
    private final PomInspector poms = new PomInspector();
    private final ScanOutcomeInterpreter interpreter = new ScanOutcomeInterpreter();
    private final PublicationGateEvaluator gates = new PublicationGateEvaluator();

    public RemediationPipeline(IronJavaProperties properties, RemediationRepository remediations, FindingRepository findings,
                               ScannerReportRepository reports, RepositoryCatalog catalog, RepositoryGateway gateway,
                               CommandRunner runner, RecommendationClient recommendations, AuditService audit,
                               QueueService queue, WorkspaceManager workspaces, TransactionTemplate tx) {
        this.properties = properties;
        this.remediations = remediations;
        this.findings = findings;
        this.reports = reports;
        this.catalog = catalog;
        this.gateway = gateway;
        this.runner = runner;
        this.recommendations = recommendations;
        this.audit = audit;
        this.queue = queue;
        this.workspaces = workspaces;
        this.tx = tx;
    }

    public record Created(UUID id, boolean reused) {
    }

    /** Terminal outcomes that may be followed by a new attempt. An opened pull request stays unique. */
    private static final List<String> INACTIVE_DEDUP_STATES = List.of("FAILED", "CANCELLED", "INTERRUPTED", "BLOCKED");

    public Created create(String actor, UUID findingId, String requestedVersion, String idempotencyKey) {
        FindingEntity finding = findings.findById(findingId).orElseThrow(() -> new RequestExceptions.Unsupported("UNKNOWN_FINDING"));
        if (!"ELIGIBLE".equals(finding.getEligibility())) {
            throw new RequestExceptions.Unsupported("NOT_ELIGIBLE");
        }
        List<String> candidates = JsonCodec.readStrings(finding.getCandidates());
        String candidate = requestedVersion == null || requestedVersion.isBlank() ? candidates.get(0) : requestedVersion;
        if (!candidates.contains(candidate)) {
            throw new RequestExceptions.Unsupported("VERSION_NOT_IN_CANDIDATE_LIST");
        }
        var repository = catalog.require(finding.getRepositoryId());
        String currentSha;
        try {
            currentSha = gateway.resolveBaseSha(repository);
        } catch (RepositoryAccessException exception) {
            throw new RequestExceptions.Conflict("REPOSITORY_INACCESSIBLE");
        }
        if (!currentSha.equals(finding.getBaseSha())) {
            throw new RequestExceptions.Conflict("STALE_BASE");
        }
        String dedup = finding.getRepositoryId() + "|" + finding.getBaseSha() + "|" + finding.getCoordinate()
                + "|" + finding.getPrimaryAdvisoryId() + "|" + candidate;
        var existing = remediations.findFirstByDedupKeyAndStateNotIn(dedup, INACTIVE_DEDUP_STATES);
        if (existing.isPresent()) {
            return new Created(existing.get().getId(), true);
        }
        RemediationRunEntity run = new RemediationRunEntity();
        run.setId(UUID.randomUUID());
        run.setRepositoryId(finding.getRepositoryId());
        run.setFindingId(finding.getId());
        run.setScanRunId(finding.getScanRunId());
        run.setBaseSha(finding.getBaseSha());
        run.setCoordinate(finding.getCoordinate());
        run.setAdvisoryId(finding.getPrimaryAdvisoryId());
        run.setPreviousVersion(finding.getVersion());
        run.setCandidateVersion(candidate);
        run.setState(RunState.QUEUED.name());
        run.setDedupKey(dedup);
        run.setActor(actor);
        run.setIdempotencyKey(idempotencyKey);
        Instant now = Instant.now();
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        try {
            remediations.saveAndFlush(run);
        } catch (DataIntegrityViolationException exception) {
            var winner = remediations.findFirstByDedupKeyAndStateNotIn(dedup, INACTIVE_DEDUP_STATES);
            if (winner.isPresent()) {
                return new Created(winner.get().getId(), true);
            }
            throw exception;
        }
        audit.record(actor, "REMEDIATION_QUEUED", run.getId(), dedup);
        return new Created(run.getId(), false);
    }

    public void runAll(UUID id) {
        runPlanAndPatch(id);
        RemediationRunEntity mid = remediations.findById(id).orElseThrow();
        if (RunState.VERIFYING.name().equals(mid.getState())) {
            runVerify(id);
        }
        RemediationRunEntity after = remediations.findById(id).orElseThrow();
        if (RunState.PUBLISHING.name().equals(after.getState()) && !after.isPublishAttempted()) {
            runPublish(id);
        }
    }

    public void runPlanAndPatch(UUID id) {
        if (!allows(Role.CONTROL)) {
            return;
        }
        if (!Boolean.TRUE.equals(tx.execute(status -> claim(id, RunState.QUEUED, RunState.PLANNING)))) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        ScheduledExecutorService heart = heartbeat(id, cancel);
        try {
            RemediationRunEntity run = remediations.findById(id).orElseThrow();
            FindingEntity finding = findings.findById(run.getFindingId()).orElseThrow();
            if (cancel.get() || run.isCancelRequested()) {
                finish(id, RunState.CANCELLED, null, null);
                return;
            }
            var repository = catalog.require(run.getRepositoryId());
            if (!gateway.resolveBaseSha(repository).equals(run.getBaseSha())) {
                finish(id, RunState.BLOCKED, ReasonCode.STALE_BASE, null);
                return;
            }
            List<String> candidates = JsonCodec.readStrings(finding.getCandidates());
            List<String> evidenceIds = JsonCodec.readStrings(finding.getAliases());
            if (evidenceIds.isEmpty()) {
                evidenceIds = List.of(finding.getPrimaryAdvisoryId());
            }
            SourceSnapshot snapshot = gateway.fetch(repository, run.getBaseSha());
            String pom;
            try {
                pom = Files.readString(snapshot.root().resolve("pom.xml"));
            } finally {
                Tree.delete(snapshot.root());
            }
            RecommendationClient.Request request = new RecommendationClient.Request(
                    finding.getId().toString(), finding.getCoordinate(), finding.getVersion(),
                    poms.dependencyExcerpt(pom, finding.getGroupId(), finding.getArtifactId()),
                    JsonCodec.readStrings(finding.getAdvisoryIds()), finding.getAffectedRanges(),
                    candidates, evidenceIds);
            RecommendationClient.Result result;
            try {
                result = recommendations.recommend(request);
            } catch (RecommendationClient.ModelUnavailableException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.MODEL_UNAVAILABLE, null);
                return;
            } catch (RecommendationClient.BudgetExceededException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.BUDGET_EXCEEDED, null);
                return;
            } catch (RecommendationValidator.RecommendationRejectedException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.MODEL_OUTPUT_REJECTED, exception.getMessage());
                return;
            }
            if (!run.getCandidateVersion().equals(result.recommendation().candidateVersion())
                    && !candidates.contains(result.recommendation().candidateVersion())) {
                finish(id, RunState.BLOCKED, ReasonCode.MODEL_OUTPUT_REJECTED, null);
                return;
            }
            String chosen = result.recommendation().candidateVersion();
            move(id, RunState.PATCHING);
            PomPatchService.Patch patch;
            try {
                patch = patcher.apply(pom, finding.getGroupId(), finding.getArtifactId(), chosen);
            } catch (PatchRejectedException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.DIFF_REJECTED, exception.code());
                return;
            }
            String diff = UnifiedDiff.of("pom.xml", pom, patch.pom());
            String hash = Digests.sha256(diff);
            String recommendationJson = JsonCodec.write(result.recommendation());
            String usage = JsonCodec.write(result.usage());
            tx.executeWithoutResult(status -> {
                RemediationRunEntity entity = remediations.lockById(id).orElseThrow();
                entity.setCandidateVersion(chosen);
                entity.setPatchDiff(diff);
                entity.setPatchHash(hash);
                entity.setPatchedPom(patch.pom());
                entity.setRecommendation(recommendationJson);
                entity.setModelUsage(usage);
                entity.setUpdatedAt(Instant.now());
            });
            move(id, RunState.VERIFYING);
            release(id);
        } catch (StaleBaseException exception) {
            finish(id, RunState.BLOCKED, ReasonCode.STALE_BASE, null);
        } catch (RepositoryAccessException exception) {
            finish(id, RunState.FAILED, ReasonCode.REPOSITORY_INACCESSIBLE, null);
        } catch (Exception exception) {
            finish(id, RunState.FAILED, ReasonCode.POLICY_VIOLATION, exception.getClass().getSimpleName());
        } finally {
            heart.shutdownNow();
        }
    }

    public void runVerify(UUID id) {
        if (!allows(Role.WORKER)) {
            return;
        }
        if (!Boolean.TRUE.equals(tx.execute(status -> claim(id, RunState.VERIFYING, RunState.VERIFYING)))) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean();
        ScheduledExecutorService heart = heartbeat(id, cancel);
        try {
            RemediationRunEntity run = remediations.findById(id).orElseThrow();
            FindingEntity finding = findings.findById(run.getFindingId()).orElseThrow();
            var repository = catalog.require(run.getRepositoryId());
            var workspace = workspaces.create(id);
            try {
                SourceSnapshot snapshot = gateway.fetch(repository, run.getBaseSha());
                try {
                    RoutingRepositoryGateway.copyTree(snapshot.root(), workspace);
                } finally {
                    Tree.delete(snapshot.root());
                }
                Files.writeString(workspace.resolve("pom.xml"), run.getPatchedPom());
                if (!Digests.sha256(UnifiedDiff.of("pom.xml", originalPom(run), run.getPatchedPom())).equals(run.getPatchHash())
                        && !Files.readString(workspace.resolve("pom.xml")).equals(run.getPatchedPom())) {
                    finish(id, RunState.BLOCKED, ReasonCode.DIFF_REJECTED, null);
                    return;
                }
                String pom = run.getPatchedPom();
                if (ExtensionGuard.rejection(workspace, pom) != null || MavenOutputParser.skipFlags(pom)) {
                    finish(id, RunState.BLOCKED, MavenOutputParser.skipFlags(pom)
                            ? ReasonCode.INSUFFICIENT_TEST_EVIDENCE : ReasonCode.UNSUPPORTED_MAVEN_EXTENSIONS, null);
                    return;
                }
                Duration timeout = Duration.ofSeconds(properties.getLimits().getStageTimeoutSeconds());
                CommandRunner.Execution resolve = runner.resolve(workspace, timeout, cancel::get);
                if (stopIfHalted(id, cancel, resolve)) {
                    return;
                }
                CommandRunner.Execution tests = runner.verify(workspace, timeout, cancel::get);
                if (stopIfHalted(id, cancel, tests)) {
                    return;
                }
                MavenOutputParser.TestCounts counts = MavenOutputParser.parse(tests.output());
                CommandRunner.Execution scan = runner.scan(workspace, timeout, cancel::get);
                if (stopIfHalted(id, cancel, scan)) {
                    return;
                }
                ScannerOutcome outcome = interpreter.interpret(scan.exitCode(), scan.output(), "");
                storeReport(id, run.getScanRunId(), scan, outcome.complete());
                Set<String> baselinePairs = pairsForScan(run.getScanRunId());
                Set<String> candidatePairs = outcome.complete() ? pairs(outcome.parsed()) : Set.of();
                boolean targetPresent = outcome.complete() && stillPresent(outcome.parsed(), finding);
                boolean newPairs = outcome.complete() && introducesPair(baselinePairs, candidatePairs, finding.getCoordinate());
                boolean feedChanged = outcome.complete() && feedMoved(baselinePairs, candidatePairs, finding.getCoordinate());
                String branchSha;
                try {
                    branchSha = gateway.resolveBaseSha(repository);
                } catch (RepositoryAccessException exception) {
                    branchSha = "";
                }
                var report = gates.evaluate(new PublicationGateEvaluator.Input(
                        true, true, true,
                        resolve.exitCode() == 0 && !resolve.runnerUnavailable(),
                        tests.exitCode() == 0,
                        counts != null && counts.executed(),
                        MavenOutputParser.skipFlags(pom),
                        outcome.complete(),
                        targetPresent,
                        newPairs,
                        feedChanged,
                        false,
                        cancel.get(),
                        false,
                        tests.timedOut() || resolve.timedOut() || scan.timedOut(),
                        branchSha,
                        run.getBaseSha()));
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("tools", PinnedVersions.asMap(properties.getRunner().getImage()));
                evidence.put("isolation", tests.isolation());
                evidence.put("resolutionMode", CommandProfiles.RESOLUTION_MODE);
                evidence.put("exclusions", CommandProfiles.EXCLUSIONS);
                evidence.put("recommendationSource", recommendationSource(run));
                evidence.put("patchHash", run.getPatchHash());
                evidence.put("resolve", view(resolve, null));
                evidence.put("tests", view(tests, counts));
                evidence.put("gates", report.gates());
                evidence.put("baselinePairs", baselinePairs);
                evidence.put("candidatePairs", candidatePairs);
                evidence.put("publishable", report.publishable());
                String evidenceJson = JsonCodec.write(evidence);
                if (tests.exitCode() != 0) {
                    finish(id, RunState.FAILED, ReasonCode.TESTS_FAILED, evidenceJson);
                    return;
                }
                if (!report.publishable()) {
                    ReasonCode code = report.reasonCode() == null ? ReasonCode.POLICY_VIOLATION : report.reasonCode();
                    RunState state = code == ReasonCode.TESTS_FAILED || code == ReasonCode.CANDIDATE_RESOLUTION_FAILED
                            ? RunState.FAILED : RunState.BLOCKED;
                    finish(id, state, code, evidenceJson);
                    return;
                }
                tx.executeWithoutResult(status -> {
                    RemediationRunEntity entity = remediations.lockById(id).orElseThrow();
                    if (!RunState.VERIFYING.name().equals(entity.getState())) {
                        return;
                    }
                    entity.setVerification(evidenceJson);
                    entity.setBranchName(branchName(entity));
                    Transitions.require(RunKind.REMEDIATION, RunState.VERIFYING, RunState.PUBLISHING);
                    entity.setState(RunState.PUBLISHING.name());
                    entity.setPublishAttempted(false);
                    entity.setLeaseOwner(null);
                    entity.setLeaseUntil(null);
                    entity.setUpdatedAt(Instant.now());
                    audit.record(entity.getActor(), "PUBLISHING", id, "verification passed; publish not started");
                });
            } finally {
                workspaces.delete(id);
            }
        } catch (Exception exception) {
            finish(id, RunState.FAILED, ReasonCode.POLICY_VIOLATION, exception.getClass().getSimpleName());
        } finally {
            heart.shutdownNow();
        }
    }

    public void runPublish(UUID id) {
        if (!allows(Role.CONTROL)) {
            return;
        }
        RemediationRunEntity preview = remediations.findById(id).orElse(null);
        if (preview == null || !RunState.PUBLISHING.name().equals(preview.getState()) || preview.isPublishAttempted()) {
            return;
        }
        if (!gateway.publishConfigured(catalog.require(preview.getRepositoryId()))) {
            finish(id, RunState.BLOCKED, ReasonCode.PUBLICATION_NOT_CONFIGURED, preview.getVerification());
            return;
        }
        Boolean claimed = tx.execute(status -> {
            RemediationRunEntity run = remediations.lockById(id).orElse(null);
            if (run == null || !RunState.PUBLISHING.name().equals(run.getState()) || run.isPublishAttempted()) {
                return false;
            }
            if (queue.paused()) {
                return false;
            }
            Instant now = Instant.now();
            run.setPublishAttempted(true);
            run.setLeaseOwner(owner);
            run.setLeaseUntil(now.plusSeconds(properties.getLimits().getLeaseSeconds()));
            run.setUpdatedAt(now);
            audit.record(run.getActor(), "PUBLISH_ATTEMPT", id, "publish attempted; interruption will not resume");
            return true;
        });
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        try {
            RemediationRunEntity run = remediations.findById(id).orElseThrow();
            var repository = catalog.require(run.getRepositoryId());
            try {
                var published = gateway.publish(repository, new PublishRequest(
                        run.getBaseSha(), run.getBranchName(), "pom.xml", run.getPatchedPom(),
                        "IronJava: upgrade " + run.getCoordinate() + " " + run.getPreviousVersion() + " to " + run.getCandidateVersion(),
                        body(run), run.getPatchHash()));
                tx.executeWithoutResult(status -> {
                    RemediationRunEntity entity = remediations.lockById(id).orElseThrow();
                    Transitions.require(RunKind.REMEDIATION, RunState.PUBLISHING, RunState.PR_OPENED);
                    entity.setState(RunState.PR_OPENED.name());
                    entity.setPrUrl(published.url());
                    entity.setPrNumber(published.number());
                    entity.setFinishedAt(Instant.now());
                    entity.setUpdatedAt(Instant.now());
                    entity.setLeaseOwner(null);
                    entity.setLeaseUntil(null);
                    audit.record(entity.getActor(), "PR_OPENED", id, published.url());
                });
            } catch (PublicationNotConfiguredException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.PUBLICATION_NOT_CONFIGURED, run.getVerification());
            } catch (PublishReconcileException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.PUBLISH_RECONCILE_REQUIRED, run.getVerification());
            } catch (StaleBaseException exception) {
                finish(id, RunState.BLOCKED, ReasonCode.STALE_BASE, run.getVerification());
            }
        } catch (Exception exception) {
            finish(id, RunState.FAILED, ReasonCode.POLICY_VIOLATION, exception.getClass().getSimpleName());
        }
    }

    private String originalPom(RemediationRunEntity run) {
        String patched = run.getPatchedPom();
        String previous = run.getPreviousVersion();
        String candidate = run.getCandidateVersion();
        int index = patched.indexOf("<version>" + candidate + "</version>");
        if (index < 0) {
            return patched;
        }
        return patched.substring(0, index) + "<version>" + previous + "</version>" + patched.substring(index + ("<version>" + candidate + "</version>").length());
    }

    private boolean stopIfHalted(UUID id, AtomicBoolean cancel, CommandRunner.Execution execution) {
        if (cancel.get() || execution.cancelled()) {
            finish(id, RunState.CANCELLED, null, null);
            return true;
        }
        if (execution.runnerUnavailable()) {
            finish(id, RunState.FAILED, ReasonCode.RUNNER_UNAVAILABLE, null);
            return true;
        }
        if (execution.timedOut()) {
            finish(id, RunState.FAILED, ReasonCode.LIMIT_EXCEEDED, null);
            return true;
        }
        return false;
    }

    private void storeReport(UUID remediationId, UUID scanId, CommandRunner.Execution scan, boolean complete) {
        tx.executeWithoutResult(status -> {
            ScannerReportEntity report = new ScannerReportEntity();
            report.setId(UUID.randomUUID());
            report.setScanRunId(scanId);
            report.setRemediationRunId(remediationId);
            report.setPhase("CANDIDATE");
            report.setRawOutput(scan.output() == null ? "" : scan.output());
            report.setScannerVersion(PinnedVersions.OSV_SCANNER);
            report.setExitCode(scan.exitCode());
            report.setComplete(complete);
            report.setCreatedAt(Instant.now());
            reports.save(report);
        });
    }

    private Set<String> pairsForScan(UUID scanId) {
        return reports.findByScanRunId(scanId).stream()
                .filter(report -> "BASELINE".equals(report.getPhase()) && report.isComplete())
                .findFirst()
                .map(report -> pairs(new dev.ironjava.scan.OsvReportParser().parse(report.getRawOutput())))
                .orElse(Set.of());
    }

    static Set<String> pairs(OsvReportParser.Parsed parsed) {
        Set<String> pairs = new LinkedHashSet<>();
        for (OsvReportParser.Advisory advisory : parsed.advisories()) {
            pairs.add(advisory.coordinate() + "|" + advisory.primaryId());
            for (String alias : advisory.aliases()) {
                pairs.add(advisory.coordinate() + "|" + alias);
            }
        }
        return pairs;
    }

    private boolean stillPresent(OsvReportParser.Parsed parsed, FindingEntity finding) {
        List<String> ids = JsonCodec.readStrings(finding.getAliases());
        for (OsvReportParser.Advisory advisory : parsed.advisories()) {
            if (!finding.getCoordinate().equals(advisory.coordinate())) {
                continue;
            }
            if (finding.getPrimaryAdvisoryId().equals(advisory.primaryId()) || advisory.aliases().contains(finding.getPrimaryAdvisoryId())) {
                return true;
            }
            for (String id : ids) {
                if (advisory.aliases().contains(id) || id.equals(advisory.primaryId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean introducesPair(Set<String> baseline, Set<String> candidate, String coordinate) {
        for (String pair : candidate) {
            if (pair.startsWith(coordinate + "|")) {
                if (!baseline.contains(pair)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean feedMoved(Set<String> baseline, Set<String> candidate, String patchedCoordinate) {
        Set<String> coordinates = new LinkedHashSet<>();
        baseline.forEach(pair -> coordinates.add(pair.substring(0, pair.indexOf('|'))));
        candidate.forEach(pair -> coordinates.add(pair.substring(0, pair.indexOf('|'))));
        for (String coordinate : coordinates) {
            if (coordinate.equals(patchedCoordinate)) {
                continue;
            }
            if (!ids(baseline, coordinate).equals(ids(candidate, coordinate))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> ids(Set<String> pairs, String coordinate) {
        Set<String> ids = new LinkedHashSet<>();
        String prefix = coordinate + "|";
        for (String pair : pairs) {
            if (pair.startsWith(prefix)) {
                ids.add(pair.substring(prefix.length()));
            }
        }
        return ids;
    }

    private String recommendationSource(RemediationRunEntity run) {
        if (run.getRecommendation() == null) {
            return "";
        }
        try {
            return JsonCodec.read(run.getRecommendation(), RecommendationValidator.Recommendation.class).source();
        } catch (Exception exception) {
            return "";
        }
    }

    private String body(RemediationRunEntity run) {
        return PullRequestBody.render(
                run.getCoordinate(), run.getPreviousVersion(), run.getCandidateVersion(), run.getAdvisoryId(),
                "The dependency is a direct production dependency with an explicit version in the root pom.xml, the advisory has an affected-version relationship, and the candidate stays inside the current major version.",
                "Only the approved version element in pom.xml changed. Patch hash " + run.getPatchHash() + ".",
                run.getBaseSha(), run.getPatchHash(), JsonCodec.write(PinnedVersions.asMap(properties.getRunner().getImage())),
                Instant.now().toString(), "baseline verify passed", "see gate results in the run evidence",
                "see verification evidence", CommandProfiles.RESOLUTION_MODE, CommandProfiles.EXCLUSIONS,
                "No separate integration-test profile is executed. mvn verify runs the tests bound in the repository.",
                run.getId().toString(), "Evidence is stored on the IronJava run and is not deleted with the workspace.");
    }

    public boolean requestCancel(UUID id, String actor) {
        Boolean cancelled = tx.execute(status -> {
            RemediationRunEntity run = remediations.lockById(id).orElse(null);
            if (run == null || RunState.valueOf(run.getState()).terminal()) {
                return false;
            }
            if (RunState.PUBLISHING.name().equals(run.getState()) && run.isPublishAttempted()) {
                return false;
            }
            run.setCancelRequested(true);
            run.setUpdatedAt(Instant.now());
            if (RunState.QUEUED.name().equals(run.getState()) && run.getLeaseOwner() == null) {
                Transitions.require(RunKind.REMEDIATION, RunState.QUEUED, RunState.CANCELLED);
                run.setState(RunState.CANCELLED.name());
                run.setFinishedAt(Instant.now());
                run.setLeaseUntil(null);
            }
            audit.record(actor, "CANCEL_REQUESTED", id, run.getState());
            return true;
        });
        return Boolean.TRUE.equals(cancelled);
    }

    public String owner() {
        return owner;
    }

    private Map<String, Object> view(CommandRunner.Execution execution, MavenOutputParser.TestCounts counts) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("exitCode", execution.exitCode());
        view.put("elapsedMillis", execution.elapsedMillis());
        view.put("isolation", execution.isolation());
        view.put("logExcerpt", execution.output());
        if (counts != null) {
            view.put("testsRun", counts.run());
            view.put("failures", counts.failures());
            view.put("errors", counts.errors());
            view.put("skipped", counts.skipped());
        }
        return view;
    }

    private boolean claim(UUID id, RunState expected, RunState next) {
        RemediationRunEntity run = remediations.lockById(id).orElse(null);
        if (run == null || !expected.name().equals(run.getState()) || queue.paused()) {
            return false;
        }
        if (run.getLeaseUntil() != null && run.getLeaseUntil().isAfter(Instant.now()) && run.getLeaseOwner() != null
                && !owner.equals(run.getLeaseOwner())) {
            return false;
        }
        if (expected != next) {
            Transitions.require(RunKind.REMEDIATION, expected, next);
            run.setState(next.name());
        }
        Instant now = Instant.now();
        if (run.getStartedAt() == null) {
            run.setStartedAt(now);
        }
        run.setLeaseOwner(owner);
        run.setLeaseUntil(now.plusSeconds(properties.getLimits().getLeaseSeconds()));
        run.setUpdatedAt(now);
        audit.record(run.getActor(), next.name(), id, "claimed");
        return true;
    }

    private void move(UUID id, RunState to) {
        tx.executeWithoutResult(status -> {
            RemediationRunEntity run = remediations.lockById(id).orElseThrow();
            RunState from = RunState.valueOf(run.getState());
            if (from == to) {
                return;
            }
            Transitions.require(RunKind.REMEDIATION, from, to);
            run.setState(to.name());
            run.setUpdatedAt(Instant.now());
            if (to == RunState.VERIFYING) {
                run.setLeaseOwner(null);
                run.setLeaseUntil(null);
            }
            audit.record(run.getActor(), to.name(), id, to.name());
        });
    }

    private void finish(UUID id, RunState state, ReasonCode code, String verification) {
        tx.executeWithoutResult(status -> {
            RemediationRunEntity run = remediations.lockById(id).orElse(null);
            if (run == null || RunState.valueOf(run.getState()).terminal()) {
                return;
            }
            Transitions.require(RunKind.REMEDIATION, RunState.valueOf(run.getState()), state);
            Instant now = Instant.now();
            run.setState(state.name());
            run.setReasonCode(code == null ? null : code.name());
            run.setReasonMessage(code == null ? null : ReasonMessages.userMessage(code));
            if (verification != null) {
                run.setVerification(verification);
            }
            run.setFinishedAt(now);
            run.setUpdatedAt(now);
            run.setLeaseOwner(null);
            run.setLeaseUntil(null);
            audit.record(run.getActor(), state.name(), id, code == null ? state.name() : code.name());
        });
    }

    private void release(UUID id) {
        tx.executeWithoutResult(status -> remediations.findById(id).ifPresent(run -> {
            if (!RunState.valueOf(run.getState()).terminal()) {
                run.setLeaseOwner(null);
                run.setLeaseUntil(null);
            }
        }));
    }

    private ScheduledExecutorService heartbeat(UUID id, AtomicBoolean cancel) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ironjava-lease");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> tx.executeWithoutResult(status -> remediations.findById(id).ifPresent(run -> {
            cancel.set(run.isCancelRequested());
            if (!RunState.valueOf(run.getState()).terminal() && owner.equals(run.getLeaseOwner())) {
                run.setLeaseUntil(Instant.now().plusSeconds(properties.getLimits().getLeaseSeconds()));
            }
        })), 5, 10, TimeUnit.SECONDS);
        return executor;
    }

    private boolean allows(Role role) {
        return properties.getRole() == Role.ALL || properties.getRole() == role;
    }

    private String branchName(RemediationRunEntity run) {
        String artifact = run.getCoordinate().substring(run.getCoordinate().indexOf(':') + 1);
        String raw = "ironjava/" + artifact + "-" + run.getAdvisoryId() + "-" + run.getId().toString().substring(0, 8);
        return raw.replaceAll("[^A-Za-z0-9._/-]", "-");
    }
}
