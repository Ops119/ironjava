package dev.ironjava.web;

import dev.ironjava.audit.LogRedactor;
import dev.ironjava.orchestrator.AuditService;
import dev.ironjava.orchestrator.Digests;
import dev.ironjava.orchestrator.IdempotencyService;
import dev.ironjava.orchestrator.QueueService;
import dev.ironjava.orchestrator.RemediationPipeline;
import dev.ironjava.orchestrator.RepositoryCatalog;
import dev.ironjava.orchestrator.RequestExceptions;
import dev.ironjava.orchestrator.ScanPipeline;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.ScanRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.UUID;

@Controller
public class DashboardController {
    private final RepositoryCatalog catalog;
    private final ScanPipeline scans;
    private final RemediationPipeline remediations;
    private final ScanRunRepository scanRepository;
    private final FindingRepository findings;
    private final RemediationRepository remediationRepository;
    private final QueueService queue;
    private final AuditService audit;
    private final IdempotencyService idempotency;

    public DashboardController(RepositoryCatalog catalog, ScanPipeline scans, RemediationPipeline remediations,
                               ScanRunRepository scanRepository, FindingRepository findings,
                               RemediationRepository remediationRepository, QueueService queue, AuditService audit,
                               IdempotencyService idempotency) {
        this.catalog = catalog;
        this.scans = scans;
        this.remediations = remediations;
        this.scanRepository = scanRepository;
        this.findings = findings;
        this.remediationRepository = remediationRepository;
        this.queue = queue;
        this.audit = audit;
        this.idempotency = idempotency;
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/dashboard";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("repositories", catalog.all());
        model.addAttribute("scans", scanRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 25)));
        model.addAttribute("paused", queue.paused());
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "dashboard";
    }

    @GetMapping("/findings")
    public String findings(@RequestParam(required = false) UUID scan, Model model) {
        model.addAttribute("scans", scanRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 25)));
        model.addAttribute("selectedScan", scan == null ? null : scan.toString());
        model.addAttribute("findings", scan == null ? java.util.List.of() : findings.findByScanRunIdOrderByCoordinateAsc(scan));
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "findings";
    }

    @GetMapping("/runs")
    public String runs(Model model) {
        model.addAttribute("runs", remediationRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 25)));
        return "runs";
    }

    @GetMapping("/runs/{id}")
    public String run(@PathVariable UUID id, Model model) {
        var run = remediationRepository.findById(id).orElse(null);
        model.addAttribute("run", run);
        model.addAttribute("audit", audit.forRun(id));
        model.addAttribute("verification", run == null ? "" : LogRedactor.redact(run.getVerification()));
        model.addAttribute("recommendation", run == null ? "" : LogRedactor.redact(run.getRecommendation()));
        model.addAttribute("diff", run == null ? "" : run.getPatchDiff());
        model.addAttribute("idempotencyKey", UUID.randomUUID().toString());
        return "run";
    }

    @PostMapping("/dashboard/scans")
    public String startScan(Authentication authentication, @RequestParam String repositoryId,
                             @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        String actor = authentication.getName();
        Map<String, Object> body = Map.of("repositoryId", repositoryId);
        String hash = Digests.sha256(dev.ironjava.json.JsonCodec.write(body));
        IdempotencyService.Begin begin = idempotency.begin(actor, "SCAN", idempotencyKey, hash);
        if (begin instanceof IdempotencyService.Reject reject) {
            redirect.addFlashAttribute("notice", reject.code());
            return "redirect:/dashboard";
        }
        if (begin instanceof IdempotencyService.Replay) {
            redirect.addFlashAttribute("notice", "Replayed the previous scan request.");
            return "redirect:/dashboard";
        }
        try {
            UUID id = scans.create(actor, repositoryId, idempotencyKey);
            idempotency.store(actor, "SCAN", idempotencyKey, hash, 202, id.toString(), id);
            redirect.addFlashAttribute("notice", "Scan queued " + id);
        } catch (RequestExceptions.Unsupported | RequestExceptions.Conflict exception) {
            redirect.addFlashAttribute("notice", exception.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/findings/remediate")
    public String remediate(Authentication authentication, @RequestParam UUID findingId,
                             @RequestParam(required = false) String candidateVersion,
                             @RequestParam String idempotencyKey, RedirectAttributes redirect) {
        String actor = authentication.getName();
        String version = candidateVersion == null ? "" : candidateVersion;
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("findingId", findingId.toString());
        body.put("candidateVersion", version);
        String hash = Digests.sha256(dev.ironjava.json.JsonCodec.write(body));
        IdempotencyService.Begin begin = idempotency.begin(actor, "REMEDIATE", idempotencyKey, hash);
        if (begin instanceof IdempotencyService.Reject reject) {
            redirect.addFlashAttribute("notice", reject.code());
            return "redirect:/findings";
        }
        if (begin instanceof IdempotencyService.Replay replay) {
            redirect.addFlashAttribute("notice", "Replayed the previous remediation request.");
            return "redirect:/runs";
        }
        try {
            RemediationPipeline.Created created = remediations.create(actor, findingId, version, idempotencyKey);
            idempotency.store(actor, "REMEDIATE", idempotencyKey, hash, created.reused() ? 200 : 202,
                    created.id().toString(), created.id());
            redirect.addFlashAttribute("notice", created.reused() ? "Existing remediation reused." : "Remediation queued.");
            return "redirect:/runs/" + created.id();
        } catch (RequestExceptions.Unsupported | RequestExceptions.Conflict exception) {
            redirect.addFlashAttribute("notice", exception.getMessage());
            return "redirect:/findings";
        }
    }

    @PostMapping("/runs/{id}/cancel")
    public String cancel(Authentication authentication, @PathVariable UUID id, @RequestParam String idempotencyKey,
                          RedirectAttributes redirect) {
        boolean ok = remediations.requestCancel(id, authentication.getName());
        redirect.addFlashAttribute("notice", ok ? "Cancellation requested." : "This run can no longer be cancelled.");
        return "redirect:/runs/" + id;
    }

    @PostMapping("/dashboard/queue")
    public String pause(Authentication authentication, @RequestParam boolean paused, @RequestParam String idempotencyKey,
                         RedirectAttributes redirect) {
        queue.setPaused(paused, authentication.getName());
        redirect.addFlashAttribute("notice", paused ? "Queue paused. In-flight work continues." : "Queue resumed.");
        return "redirect:/dashboard";
    }

    @PostMapping("/dashboard/scans/{id}/cancel")
    public String cancelScan(Authentication authentication, @PathVariable UUID id, @RequestParam String idempotencyKey,
                              RedirectAttributes redirect) {
        boolean ok = scans.requestCancel(id, authentication.getName());
        redirect.addFlashAttribute("notice", ok ? "Cancellation requested." : "This scan can no longer be cancelled.");
        return "redirect:/dashboard";
    }
}
