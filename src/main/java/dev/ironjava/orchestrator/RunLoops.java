package dev.ironjava.orchestrator;

import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.domain.Domain.Role;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.ScanRunRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class RunLoops {
    private final IronJavaProperties properties;
    private final ScanRunRepository scans;
    private final RemediationRepository remediations;
    private final ScanPipeline scanPipeline;
    private final RemediationPipeline remediationPipeline;
    private final RunMaintenance maintenance;
    private final WorkspaceManager workspaces;

    public RunLoops(IronJavaProperties properties, ScanRunRepository scans, RemediationRepository remediations,
                    ScanPipeline scanPipeline, RemediationPipeline remediationPipeline, RunMaintenance maintenance,
                    WorkspaceManager workspaces) {
        this.properties = properties;
        this.scans = scans;
        this.remediations = remediations;
        this.scanPipeline = scanPipeline;
        this.remediationPipeline = remediationPipeline;
        this.maintenance = maintenance;
        this.workspaces = workspaces;
    }

    @Scheduled(fixedDelayString = "${ironjava.loop-delay-ms:2000}")
    public void tick() {
        Instant now = Instant.now();
        var page = PageRequest.of(0, 1);
        Role role = properties.getRole();
        try {
            if (properties.isWorkerEnabled() && (role == Role.WORKER || role == Role.ALL)) {
                for (UUID id : scans.claimable(now, page)) {
                    scanPipeline.run(id);
                }
                for (UUID id : remediations.claimableVerification(now, page)) {
                    remediationPipeline.runVerify(id);
                }
            }
            if (properties.isControlLoopEnabled() && (role == Role.CONTROL || role == Role.ALL)) {
                for (UUID id : remediations.claimablePlanning(now, page)) {
                    remediationPipeline.runPlanAndPatch(id);
                }
                for (UUID id : remediations.claimablePublish(now, page)) {
                    remediationPipeline.runPublish(id);
                }
            }
            maintenance.interruptExpired();
            workspaces.purgeExpired();
        } catch (RuntimeException exception) {
            // The next tick retries. A failed claim must not stop the scheduler.
        }
    }

    @PreDestroy
    public void shutdown() {
        maintenance.interruptOwner(scanPipeline.owner(), remediationPipeline.owner());
    }
}
