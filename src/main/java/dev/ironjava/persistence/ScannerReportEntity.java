package dev.ironjava.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scanner_report")
public class ScannerReportEntity {
    @Id
    private UUID id;
    @Column(name = "scan_run_id")
    private UUID scanRunId;
    @Column(name = "remediation_run_id")
    private UUID remediationRunId;
    @Column(nullable = false)
    private String phase;
    @Column(name = "raw_output", nullable = false)
    private String rawOutput;
    @Column(name = "scanner_version")
    private String scannerVersion;
    @Column(name = "exit_code")
    private Integer exitCode;
    @Column(nullable = false)
    private boolean complete;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getScanRunId() { return scanRunId; }
    public void setScanRunId(UUID scanRunId) { this.scanRunId = scanRunId; }
    public UUID getRemediationRunId() { return remediationRunId; }
    public void setRemediationRunId(UUID remediationRunId) { this.remediationRunId = remediationRunId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getRawOutput() { return rawOutput; }
    public void setRawOutput(String rawOutput) { this.rawOutput = rawOutput; }
    public String getScannerVersion() { return scannerVersion; }
    public void setScannerVersion(String scannerVersion) { this.scannerVersion = scannerVersion; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public boolean isComplete() { return complete; }
    public void setComplete(boolean complete) { this.complete = complete; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
