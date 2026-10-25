package dev.ironjava.orchestrator;

import dev.ironjava.domain.Domain.RunKind;
import dev.ironjava.domain.Domain.RunState;
import dev.ironjava.domain.Domain.Transitions;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.RemediationRunEntity;
import dev.ironjava.persistence.ScanRunEntity;
import dev.ironjava.persistence.ScanRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class RunMaintenance {
    static final String INTERRUPT_MESSAGE =
            "The worker lease expired or the process stopped. This run was interrupted and was not resumed.";

    private final ScanRunRepository scans;
    private final RemediationRepository remediations;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public RunMaintenance(ScanRunRepository scans, RemediationRepository remediations, AuditService audit,
                          TransactionTemplate tx) {
        this.scans = scans;
        this.remediations = remediations;
        this.audit = audit;
        this.tx = tx;
    }

    public void interruptExpired() {
        Instant now = Instant.now();
        for (ScanRunEntity run : scans.expired(now)) {
            interruptScan(run.getId(), true);
        }
        for (RemediationRunEntity run : remediations.expired(now)) {
            interruptRemediation(run.getId(), true);
        }
    }

    public void interruptOwner(String scanOwner, String remediationOwner) {
        if (scanOwner != null) {
            for (ScanRunEntity run : scans.leasedBy(scanOwner)) {
                interruptScan(run.getId(), false);
            }
        }
        if (remediationOwner != null) {
            for (RemediationRunEntity run : remediations.leasedBy(remediationOwner)) {
                interruptRemediation(run.getId(), false);
            }
        }
    }

    private void interruptScan(UUID id, boolean requireExpired) {
        tx.executeWithoutResult(status -> {
            ScanRunEntity run = scans.lockById(id).orElse(null);
            if (!interruptible(run == null ? null : run.getLeaseOwner(), run == null ? null : run.getLeaseUntil(),
                    run == null ? null : RunState.valueOf(run.getState()), requireExpired)) {
                return;
            }
            Transitions.require(RunKind.SCAN, RunState.valueOf(run.getState()), RunState.INTERRUPTED);
            Instant now = Instant.now();
            run.setState(RunState.INTERRUPTED.name());
            run.setReasonMessage(INTERRUPT_MESSAGE);
            run.setLeaseOwner(null);
            run.setLeaseUntil(null);
            run.setUpdatedAt(now);
            run.setFinishedAt(now);
            audit.record("system", "INTERRUPTED", id, "lease");
        });
    }

    private void interruptRemediation(UUID id, boolean requireExpired) {
        tx.executeWithoutResult(status -> {
            RemediationRunEntity run = remediations.lockById(id).orElse(null);
            if (run == null || !interruptible(run.getLeaseOwner(), run.getLeaseUntil(),
                    RunState.valueOf(run.getState()), requireExpired)) {
                return;
            }
            Transitions.require(RunKind.REMEDIATION, RunState.valueOf(run.getState()), RunState.INTERRUPTED);
            Instant now = Instant.now();
            run.setState(RunState.INTERRUPTED.name());
            run.setReasonMessage(INTERRUPT_MESSAGE);
            run.setLeaseOwner(null);
            run.setLeaseUntil(null);
            run.setUpdatedAt(now);
            run.setFinishedAt(now);
            audit.record("system", "INTERRUPTED", id, run.isPublishAttempted()
                    ? "publish was already attempted; not resumed"
                    : "lease");
        });
    }

    private static boolean interruptible(String owner, Instant until, RunState state, boolean requireExpired) {
        if (owner == null || state == null || state.terminal()) {
            return false;
        }
        if (!requireExpired) {
            return true;
        }
        return until != null && until.isBefore(Instant.now());
    }
}
