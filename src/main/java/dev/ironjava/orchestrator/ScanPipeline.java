package dev.ironjava.orchestrator;

import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.config.PinnedVersions;
import dev.ironjava.domain.Domain.ReasonCode;
import dev.ironjava.domain.Domain.Role;
import dev.ironjava.domain.Domain.RunKind;
import dev.ironjava.domain.Domain.RunState;
import dev.ironjava.domain.Domain.Transitions;
import dev.ironjava.gateway.MavenMetadataClient;
import dev.ironjava.gateway.RepositoryAccessException;
import dev.ironjava.gateway.RepositoryGateway;
import dev.ironjava.gateway.RoutingRepositoryGateway;
import dev.ironjava.gateway.SourceSnapshot;
import dev.ironjava.gateway.StaleBaseException;
import dev.ironjava.gateway.Tree;
import dev.ironjava.json.JsonCodec;
import dev.ironjava.persistence.FindingEntity;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.ScanRunEntity;
import dev.ironjava.persistence.ScanRunRepository;
import dev.ironjava.persistence.ScannerReportEntity;
import dev.ironjava.persistence.ScannerReportRepository;
import dev.ironjava.policy.EligibilityPolicy;
import dev.ironjava.policy.FindingNormalizer;
import dev.ironjava.policy.PomInspector;
import dev.ironjava.scan.OsvReportParser;
import dev.ironjava.scan.ScanOutcomeInterpreter;
import dev.ironjava.scan.ScannerOutcome;
import dev.ironjava.verification.CommandRunner;
import dev.ironjava.verification.ExtensionGuard;
import dev.ironjava.verification.MavenOutputParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ScanPipeline {
    private final IronJavaProperties properties;
    private final ScanRunRepository scans;
    private final FindingRepository findings;
    private final ScannerReportRepository reports;
    private final RepositoryCatalog catalog;
    private final RepositoryGateway gateway;
    private final CommandRunner runner;
    private final MavenMetadataClient metadata;
    private final AuditService audit;
    private final QueueService queue;
    private final WorkspaceManager workspaces;
    private final TransactionTemplate tx;
    private final String owner = UUID.randomUUID().toString();
    private final EligibilityPolicy eligibility = new EligibilityPolicy();
    private final PomInspector poms = new PomInspector();
    private final ScanOutcomeInterpreter interpreter = new ScanOutcomeInterpreter();

    public ScanPipeline(IronJavaProperties properties, ScanRunRepository scans, FindingRepository findings,
                 ScannerReportRepository reports, RepositoryCatalog catalog, RepositoryGateway gateway,
                 CommandRunner runner, MavenMetadataClient metadata, AuditService audit, QueueService queue,
                 WorkspaceManager workspaces, TransactionTemplate tx) {
        this.properties = properties;
        this.scans = scans;
        this.findings = findings;
        this.reports = reports;
        this.catalog = catalog;
        this.gateway = gateway;
        this.runner = runner;
        this.metadata = metadata;
        this.audit = audit;
        this.queue = queue;
        this.workspaces = workspaces;
        this.tx = tx;
    }

    public UUID create(String actor, String repositoryId, String idempotencyKey) {
        var repository = catalog.require(repositoryId);
        ScanRunEntity entity = new ScanRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setRepositoryId(repository.id());
        entity.setAllowedBranch(repository.allowedBranch());
        entity.setState(RunState.QUEUED.name());
        entity.setActor(actor);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setToolVersions(JsonCodec.write(PinnedVersions.asMap(properties.getRunner().getImage())));
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        scans.save(entity);
        audit.record(actor, "SCAN_QUEUED", entity.getId(), repository.id());
        return entity.getId();
    }

    public void run(UUID id) {
        if (!workerRole()) {
            return;
        }
        Boolean claimed = tx.execute(status -> claim(id));
        if (!Boolean.TRUE.equals(claimed)) {
            return;
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        ScheduledExecutorService heart = heartbeat(id, cancel, true);
        Path workspace = null;
        try {
            workspace = workspaces.create(id);
            ScanRunEntity run = scans.findById(id).orElseThrow();
            var repository = catalog.require(run.getRepositoryId());
            String sha = gateway.resolveBaseSha(repository);
            tx.executeWithoutResult(status -> {
                ScanRunEntity entity = scans.findById(id).orElseThrow();
                entity.setBaseSha(sha);
                entity.setUpdatedAt(Instant.now());
            });
            if (cancel.get()) {
                finish(id, RunState.CANCELLED, null, "cancelled");
                return;
            }
            SourceSnapshot snapshot = gateway.fetch(repository, sha);
            try {
                RoutingRepositoryGateway.copyTree(snapshot.root(), workspace);
            } finally {
                Tree.delete(snapshot.root());
            }
            String pom = Files.readString(workspace.resolve("pom.xml"));
            if (ExtensionGuard.rejection(workspace, pom) != null) {
                finish(id, RunState.BLOCKED, ReasonCode.UNSUPPORTED_MAVEN_EXTENSIONS, null);
                return;
            }
            Duration timeout = Duration.ofSeconds(properties.getLimits().getStageTimeoutSeconds());
            CommandRunner.Execution verify = runner.verify(workspace, timeout, cancel::get);
            MavenOutputParser.TestCounts counts = MavenOutputParser.parse(verify.output());
            boolean testsExecuted = counts != null && counts.executed() && !MavenOutputParser.skipFlags(pom);
            Map<String, Object> evidence = new HashMap<>();
            evidence.put("tools", PinnedVersions.asMap(properties.getRunner().getImage()));
            evidence.put("isolation", verify.isolation());
            evidence.put("baseline", commandView(verify, counts));
            if (verify.cancelled() || cancel.get()) {
                finish(id, RunState.CANCELLED, null, JsonCodec.write(evidence));
                return;
            }
            if (verify.runnerUnavailable()) {
                finish(id, RunState.FAILED, ReasonCode.RUNNER_UNAVAILABLE, JsonCodec.write(evidence));
                return;
            }
            if (verify.timedOut()) {
                finish(id, RunState.FAILED, ReasonCode.LIMIT_EXCEEDED, JsonCodec.write(evidence));
                return;
            }
            if (verify.exitCode() != 0) {
                finish(id, RunState.FAILED, ReasonCode.BASELINE_FAILED, JsonCodec.write(evidence));
                return;
            }
            move(id, RunState.SCANNING);
            CommandRunner.Execution scan = runner.scan(workspace, timeout, cancel::get);
            if (scan.cancelled() || cancel.get()) {
                finish(id, RunState.CANCELLED, null, JsonCodec.write(evidence));
                return;
            }
            ScannerOutcome outcome = interpreter.interpret(scan.exitCode(), scan.output(), "");
            storeReport(id, scan, outcome.complete());
            evidence.put("scanIsolation", scan.isolation());
            evidence.put("scanComplete", outcome.complete());
            if (!outcome.complete()) {
                evidence.put("scannerFailure", outcome.failure());
                finish(id, RunState.FAILED, ReasonCode.SCAN_INCONCLUSIVE, JsonCodec.write(evidence));
                return;
            }
            boolean baselinePassed = testsExecuted;
            if (!baselinePassed) {
                evidence.put("baselineTests", "not observed");
            }
            writeFindings(id, sha, pom, outcome.parsed(), baselinePassed, repository.excludedDependencies());
            evidence.put("findingCount", outcome.parsed().advisories().size());
            finish(id, RunState.COMPLETED, null, JsonCodec.write(evidence));
        } catch (RepositoryAccessException | RequestExceptions.Unsupported exception) {
            finish(id, RunState.FAILED, ReasonCode.REPOSITORY_INACCESSIBLE, null);
        } catch (StaleBaseException exception) {
            finish(id, RunState.BLOCKED, ReasonCode.STALE_BASE, null);
        } catch (Exception exception) {
            finish(id, RunState.FAILED, ReasonCode.POLICY_VIOLATION, exception.getClass().getSimpleName());
        } finally {
            heart.shutdownNow();
            release(id, true);
            if (workspace != null) {
                workspaces.delete(id);
            }
        }
    }

    private boolean claim(UUID id) {
        ScanRunEntity run = scans.lockById(id).orElse(null);
        if (run == null || !RunState.QUEUED.name().equals(run.getState()) || queue.paused()) {
            return false;
        }
        Transitions.require(RunKind.SCAN, RunState.QUEUED, RunState.BASELINING);
        Instant now = Instant.now();
        run.setState(RunState.BASELINING.name());
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        run.setLeaseOwner(owner);
        run.setLeaseUntil(now.plusSeconds(properties.getLimits().getLeaseSeconds()));
        audit.record(run.getActor(), "BASELINING", id, "claimed");
        return true;
    }

    private void writeFindings(UUID scanId, String sha, String pom, OsvReportParser.Parsed parsed, boolean baselinePassed,
                               java.util.Set<String> excluded) {
        ScanRunEntity scan = scans.findById(scanId).orElseThrow();
        List<PomInspector.DirectDependency> direct = poms.directDependencies(pom);
        tx.executeWithoutResult(status -> {
            for (OsvReportParser.Advisory advisory : parsed.advisories()) {
                String[] parts = advisory.coordinate().split(":", 2);
                if (parts.length != 2) {
                    continue;
                }
                var fact = FindingNormalizer.toFact(direct, advisory, excluded.contains(advisory.coordinate()));
                String version = fact.version();
                var advisoryFact = new EligibilityPolicy.AdvisoryFact(advisory.primaryId(), advisory.ids(), advisory.aliases(),
                        advisory.severity(), advisory.ranges(), advisory.explicitVersions(), advisory.summary());
                MavenMetadataClient.Result published = metadata.list(parts[0], parts[1]);
                EligibilityPolicy.Decision decision = eligibility.decide(fact, advisoryFact, baselinePassed,
                        published.reachable(), published.versions());
                FindingEntity finding = new FindingEntity();
                finding.setId(UUID.randomUUID());
                finding.setScanRunId(scanId);
                finding.setRepositoryId(scan.getRepositoryId());
                finding.setBaseSha(sha);
                finding.setCoordinate(advisory.coordinate());
                finding.setGroupId(parts[0]);
                finding.setArtifactId(parts[1]);
                finding.setVersion(version == null ? "" : version);
                finding.setScope(fact.scope());
                finding.setDirectDependency(fact.direct());
                finding.setPomPath("pom.xml");
                finding.setVersionSource(fact.versionSource().name());
                finding.setAdvisoryIds(JsonCodec.write(advisory.ids()));
                finding.setPrimaryAdvisoryId(advisory.primaryId());
                finding.setAliases(JsonCodec.write(advisory.aliases()));
                finding.setSeverity(decision.severityDisplay());
                finding.setAffectedRanges(JsonCodec.write(advisory.ranges()));
                finding.setSummary(advisory.summary());
                finding.setEligibility(decision.eligibility().name());
                finding.setEligibilityReasons(JsonCodec.write(decision.reasons()));
                finding.setCandidates(JsonCodec.write(decision.candidates()));
                finding.setCreatedAt(Instant.now());
                findings.save(finding);
            }
        });
    }

    private void storeReport(UUID scanId, CommandRunner.Execution scan, boolean complete) {
        tx.executeWithoutResult(status -> {
            ScannerReportEntity report = new ScannerReportEntity();
            report.setId(UUID.randomUUID());
            report.setScanRunId(scanId);
            report.setPhase("BASELINE");
            report.setRawOutput(scan.output() == null ? "" : scan.output());
            report.setScannerVersion(PinnedVersions.OSV_SCANNER);
            report.setExitCode(scan.exitCode());
            report.setComplete(complete);
            report.setCreatedAt(Instant.now());
            reports.save(report);
        });
    }

    private void move(UUID id, RunState to) {
        tx.executeWithoutResult(status -> {
            ScanRunEntity run = scans.lockById(id).orElseThrow();
            RunState from = RunState.valueOf(run.getState());
            Transitions.require(RunKind.SCAN, from, to);
            run.setState(to.name());
            run.setUpdatedAt(Instant.now());
            audit.record(run.getActor(), to.name(), id, to.name());
        });
    }

    private void finish(UUID id, RunState state, ReasonCode code, String evidence) {
        tx.executeWithoutResult(status -> {
            ScanRunEntity run = scans.lockById(id).orElse(null);
            if (run == null || RunState.valueOf(run.getState()).terminal()) {
                return;
            }
            RunState from = RunState.valueOf(run.getState());
            Transitions.require(RunKind.SCAN, from, state);
            Instant now = Instant.now();
            run.setState(state.name());
            run.setReasonCode(code == null ? null : code.name());
            run.setReasonMessage(code == null ? null : dev.ironjava.domain.Domain.ReasonMessages.userMessage(code));
            if (evidence != null) {
                run.setEvidence(evidence);
            }
            run.setUpdatedAt(now);
            run.setFinishedAt(now);
            run.setLeaseOwner(null);
            run.setLeaseUntil(null);
            audit.record(run.getActor(), state.name(), id, code == null ? state.name() : code.name());
        });
    }

    private void release(UUID id, boolean scan) {
        tx.executeWithoutResult(status -> scans.findById(id).ifPresent(run -> {
            if (!RunState.valueOf(run.getState()).terminal()) {
                run.setLeaseOwner(null);
                run.setLeaseUntil(null);
            }
        }));
    }

    private ScheduledExecutorService heartbeat(UUID id, AtomicBoolean cancel, boolean scan) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ironjava-lease");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> tx.executeWithoutResult(status -> scans.findById(id).ifPresent(run -> {
            cancel.set(run.isCancelRequested());
            if (!RunState.valueOf(run.getState()).terminal() && owner.equals(run.getLeaseOwner())) {
                run.setLeaseUntil(Instant.now().plusSeconds(properties.getLimits().getLeaseSeconds()));
            }
        })), 5, 10, TimeUnit.SECONDS);
        return executor;
    }

    private boolean workerRole() {
        Role role = properties.getRole();
        return role == Role.WORKER || role == Role.ALL;
    }

    private Map<String, Object> commandView(CommandRunner.Execution execution, MavenOutputParser.TestCounts counts) {
        Map<String, Object> view = new HashMap<>();
        view.put("exitCode", execution.exitCode());
        view.put("elapsedMillis", execution.elapsedMillis());
        view.put("isolation", execution.isolation());
        view.put("command", execution.command());
        view.put("logExcerpt", execution.output());
        if (counts != null) {
            view.put("testsRun", counts.run());
            view.put("failures", counts.failures());
            view.put("errors", counts.errors());
            view.put("skipped", counts.skipped());
        }
        return view;
    }

    public boolean requestCancel(UUID id, String actor) {
        Boolean cancelled = tx.execute(status -> {
            ScanRunEntity run = scans.lockById(id).orElse(null);
            if (run == null || RunState.valueOf(run.getState()).terminal()) {
                return false;
            }
            run.setCancelRequested(true);
            run.setUpdatedAt(Instant.now());
            if (RunState.QUEUED.name().equals(run.getState()) && run.getLeaseOwner() == null) {
                Transitions.require(RunKind.SCAN, RunState.QUEUED, RunState.CANCELLED);
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
}
