package dev.ironjava.web;

import dev.ironjava.audit.LogRedactor;
import dev.ironjava.domain.Domain.ReasonCode;
import dev.ironjava.domain.Domain.ReasonMessages;
import dev.ironjava.json.JsonCodec;
import dev.ironjava.orchestrator.Digests;
import dev.ironjava.orchestrator.IdempotencyService;
import dev.ironjava.orchestrator.QueueService;
import dev.ironjava.orchestrator.RemediationPipeline;
import dev.ironjava.orchestrator.RequestExceptions;
import dev.ironjava.orchestrator.ScanPipeline;
import dev.ironjava.persistence.FindingEntity;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.RemediationRunEntity;
import dev.ironjava.persistence.ScanRunEntity;
import dev.ironjava.persistence.ScanRunRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
public class ApiController {
    private static final Set<String> SCAN_FIELDS = Set.of("repositoryId");
    private static final Set<String> REMEDIATE_FIELDS = Set.of("findingId", "candidateVersion");

    private final ScanPipeline scans;
    private final RemediationPipeline remediations;
    private final ScanRunRepository scanRepository;
    private final FindingRepository findingRepository;
    private final RemediationRepository remediationRepository;
    private final IdempotencyService idempotency;
    private final QueueService queue;

    public ApiController(ScanPipeline scans, RemediationPipeline remediations, ScanRunRepository scanRepository,
                         FindingRepository findingRepository, RemediationRepository remediationRepository,
                         IdempotencyService idempotency, QueueService queue) {
        this.scans = scans;
        this.remediations = remediations;
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.remediationRepository = remediationRepository;
        this.idempotency = idempotency;
        this.queue = queue;
    }

    @PostMapping("/scans")
    public ResponseEntity<String> createScan(Authentication authentication,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @RequestBody Map<String, Object> body) {
        if (body == null || !SCAN_FIELDS.containsAll(body.keySet()) || !(body.get("repositoryId") instanceof String repositoryId)) {
            return rejected(authentication, "SCAN", key, body, 422, "UNSUPPORTED_INPUT");
        }
        return handle(authentication, "SCAN", key, body, () -> {
            UUID id = scans.create(actor(authentication), repositoryId, key);
            return new Outcome(202, scanBody(scanRepository.findById(id).orElseThrow()), id);
        });
    }

    @GetMapping("/scans/{id}")
    public ResponseEntity<String> scan(@PathVariable UUID id) {
        return scanRepository.findById(id)
                .map(run -> ResponseEntity.ok(scanBody(run)))
                .orElseGet(() -> ResponseEntity.status(404).body(error("NOT_FOUND")));
    }

    @GetMapping("/scans/{id}/findings")
    public ResponseEntity<String> findings(@PathVariable UUID id) {
        if (scanRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(error("NOT_FOUND"));
        }
        List<Map<String, Object>> rows = findingRepository.findByScanRunIdOrderByCoordinateAsc(id).stream()
                .map(ApiController::findingBody).toList();
        return ResponseEntity.ok(JsonCodec.write(rows));
    }

    @PostMapping("/scans/{id}/cancel")
    public ResponseEntity<String> cancelScan(Authentication authentication, @PathVariable UUID id,
                                              @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return handle(authentication, "CANCEL_SCAN", key, Map.of("id", id.toString()), () -> {
            boolean ok = scans.requestCancel(id, actor(authentication));
            return new Outcome(ok ? 202 : 409, error(ok ? "CANCEL_REQUESTED" : "NOT_CANCELLABLE"), id);
        });
    }

    @PostMapping("/remediations")
    public ResponseEntity<String> createRemediation(Authentication authentication,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @RequestBody Map<String, Object> body) {
        if (body == null || !REMEDIATE_FIELDS.containsAll(body.keySet()) || !(body.get("findingId") instanceof String findingId)) {
            return rejected(authentication, "REMEDIATE", key, body, 422, "UNSUPPORTED_INPUT");
        }
        String version = body.get("candidateVersion") instanceof String text ? text : "";
        return handle(authentication, "REMEDIATE", key, body, () -> {
            UUID id = UUID.fromString(findingId);
            RemediationPipeline.Created created = remediations.create(actor(authentication), id, version, key);
            RemediationRunEntity run = remediationRepository.findById(created.id()).orElseThrow();
            return new Outcome(created.reused() ? 200 : 202, remediationBody(run), created.id());
        });
    }

    @GetMapping("/remediations/{id}")
    public ResponseEntity<String> remediation(@PathVariable UUID id) {
        return remediationRepository.findById(id)
                .map(run -> ResponseEntity.ok(remediationBody(run)))
                .orElseGet(() -> ResponseEntity.status(404).body(error("NOT_FOUND")));
    }

    @PostMapping("/remediations/{id}/cancel")
    public ResponseEntity<String> cancelRemediation(Authentication authentication, @PathVariable UUID id,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return handle(authentication, "CANCEL_REMEDIATION", key, Map.of("id", id.toString()), () -> {
            boolean ok = remediations.requestCancel(id, actor(authentication));
            return new Outcome(ok ? 202 : 409, error(ok ? "CANCEL_REQUESTED" : "NOT_CANCELLABLE"), id);
        });
    }

    @PostMapping("/queue")
    public ResponseEntity<String> queue(Authentication authentication,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestBody Map<String, Object> body) {
        if (body == null || body.size() != 1 || !(body.get("paused") instanceof Boolean paused)) {
            return rejected(authentication, "QUEUE", key, body, 422, "UNSUPPORTED_INPUT");
        }
        return handle(authentication, "QUEUE", key, body, () -> {
            queue.setPaused(paused, actor(authentication));
            return new Outcome(200, JsonCodec.write(Map.of("paused", queue.paused())), null);
        });
    }

    private ResponseEntity<String> rejected(Authentication authentication, String operation, String key,
                                             Map<String, Object> body, int status, String code) {
        return handle(authentication, operation, key, body == null ? Map.of() : body,
                () -> new Outcome(status, error(code), null));
    }

    private ResponseEntity<String> handle(Authentication authentication, String operation, String key,
                                           Map<String, Object> body, Attempt attempt) {
        String hash = Digests.sha256(JsonCodec.write(body));
        IdempotencyService.Begin begin = idempotency.begin(actor(authentication), operation, key, hash);
        if (begin instanceof IdempotencyService.Reject reject) {
            return ResponseEntity.status(reject.status()).body(error(reject.code()));
        }
        if (begin instanceof IdempotencyService.Replay replay) {
            return ResponseEntity.status(replay.status()).body(replay.body());
        }
        try {
            Outcome outcome = attempt.run();
            idempotency.store(actor(authentication), operation, key, hash, outcome.status(), outcome.body(), outcome.resourceId());
            return ResponseEntity.status(outcome.status()).body(outcome.body());
        } catch (RequestExceptions.Unsupported unsupported) {
            String payload = error(unsupported.code());
            idempotency.store(actor(authentication), operation, key, hash, 422, payload, null);
            return ResponseEntity.status(422).body(payload);
        } catch (RequestExceptions.Conflict conflict) {
            String payload = error(conflict.code());
            idempotency.store(actor(authentication), operation, key, hash, 409, payload, null);
            return ResponseEntity.status(409).body(payload);
        } catch (IllegalArgumentException exception) {
            String payload = error("UNSUPPORTED_INPUT");
            idempotency.store(actor(authentication), operation, key, hash, 422, payload, null);
            return ResponseEntity.status(422).body(payload);
        }
    }

    private static String actor(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? "anonymous" : authentication.getName();
    }

    static String error(String code) {
        String message = code;
        try {
            message = ReasonMessages.userMessage(ReasonCode.valueOf(code));
        } catch (IllegalArgumentException ignored) {
            message = code;
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return JsonCodec.write(body);
    }

    static String scanBody(ScanRunEntity run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.getId().toString());
        body.put("repositoryId", run.getRepositoryId());
        body.put("allowedBranch", run.getAllowedBranch());
        body.put("baseSha", run.getBaseSha());
        body.put("state", run.getState());
        body.put("reasonCode", run.getReasonCode());
        body.put("reasonMessage", run.getReasonMessage());
        body.put("evidence", LogRedactor.redact(run.getEvidence()));
        body.put("cancelRequested", run.isCancelRequested());
        return JsonCodec.write(body);
    }

    static String remediationBody(RemediationRunEntity run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.getId().toString());
        body.put("repositoryId", run.getRepositoryId());
        body.put("findingId", run.getFindingId().toString());
        body.put("baseSha", run.getBaseSha());
        body.put("coordinate", run.getCoordinate());
        body.put("advisoryId", run.getAdvisoryId());
        body.put("previousVersion", run.getPreviousVersion());
        body.put("candidateVersion", run.getCandidateVersion());
        body.put("state", run.getState());
        body.put("reasonCode", run.getReasonCode());
        body.put("reasonMessage", run.getReasonMessage());
        body.put("patchDiff", run.getPatchDiff());
        body.put("patchHash", run.getPatchHash());
        body.put("verification", LogRedactor.redact(run.getVerification()));
        body.put("prUrl", run.getPrUrl());
        body.put("branchName", run.getBranchName());
        body.put("publishAttempted", run.isPublishAttempted());
        body.put("recommendation", LogRedactor.redact(run.getRecommendation()));
        return JsonCodec.write(body);
    }

    static Map<String, Object> findingBody(FindingEntity finding) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", finding.getId().toString());
        body.put("coordinate", finding.getCoordinate());
        body.put("version", finding.getVersion());
        body.put("scope", finding.getScope());
        body.put("versionSource", finding.getVersionSource());
        body.put("directDependency", finding.isDirectDependency());
        body.put("primaryAdvisoryId", finding.getPrimaryAdvisoryId());
        body.put("aliases", finding.getAliases());
        body.put("severity", finding.getSeverity());
        body.put("eligibility", finding.getEligibility());
        body.put("eligibilityReasons", finding.getEligibilityReasons());
        body.put("candidates", finding.getCandidates());
        body.put("summary", finding.getSummary());
        return body;
    }

    private record Outcome(int status, String body, UUID resourceId) {
    }

    @FunctionalInterface
    private interface Attempt {
        Outcome run();
    }
}
