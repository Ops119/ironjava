package dev.ironjava.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "remediation_run")
public class RemediationRunEntity {
    @Id
    private UUID id;
    @Column(name = "repository_id", nullable = false)
    private String repositoryId;
    @Column(name = "finding_id", nullable = false)
    private UUID findingId;
    @Column(name = "scan_run_id", nullable = false)
    private UUID scanRunId;
    @Column(name = "base_sha", nullable = false)
    private String baseSha;
    @Column(nullable = false)
    private String coordinate;
    @Column(name = "advisory_id", nullable = false)
    private String advisoryId;
    @Column(name = "previous_version", nullable = false)
    private String previousVersion;
    @Column(name = "candidate_version")
    private String candidateVersion;
    @Column(nullable = false)
    private String state;
    @Column(name = "reason_code")
    private String reasonCode;
    @Column(name = "reason_message")
    private String reasonMessage;
    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;
    @Column(name = "patch_diff")
    private String patchDiff;
    @Column(name = "patch_hash")
    private String patchHash;
    @Column(name = "patched_pom")
    private String patchedPom;
    private String recommendation;
    private String verification;
    @Column(name = "pr_url")
    private String prUrl;
    @Column(name = "pr_number")
    private Integer prNumber;
    @Column(name = "branch_name")
    private String branchName;
    @Column(name = "publish_attempted", nullable = false)
    private boolean publishAttempted;
    @Column(name = "cancel_requested", nullable = false)
    private boolean cancelRequested;
    @Column(nullable = false)
    private String actor;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "model_usage")
    private String modelUsage;
    @Column(name = "lease_owner")
    private String leaseOwner;
    @Column(name = "lease_until")
    private Instant leaseUntil;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
    public UUID getFindingId() { return findingId; }
    public void setFindingId(UUID findingId) { this.findingId = findingId; }
    public UUID getScanRunId() { return scanRunId; }
    public void setScanRunId(UUID scanRunId) { this.scanRunId = scanRunId; }
    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }
    public String getCoordinate() { return coordinate; }
    public void setCoordinate(String coordinate) { this.coordinate = coordinate; }
    public String getAdvisoryId() { return advisoryId; }
    public void setAdvisoryId(String advisoryId) { this.advisoryId = advisoryId; }
    public String getPreviousVersion() { return previousVersion; }
    public void setPreviousVersion(String previousVersion) { this.previousVersion = previousVersion; }
    public String getCandidateVersion() { return candidateVersion; }
    public void setCandidateVersion(String candidateVersion) { this.candidateVersion = candidateVersion; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    public String getReasonMessage() { return reasonMessage; }
    public void setReasonMessage(String reasonMessage) { this.reasonMessage = reasonMessage; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getPatchDiff() { return patchDiff; }
    public void setPatchDiff(String patchDiff) { this.patchDiff = patchDiff; }
    public String getPatchHash() { return patchHash; }
    public void setPatchHash(String patchHash) { this.patchHash = patchHash; }
    public String getPatchedPom() { return patchedPom; }
    public void setPatchedPom(String patchedPom) { this.patchedPom = patchedPom; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
    public String getVerification() { return verification; }
    public void setVerification(String verification) { this.verification = verification; }
    public String getPrUrl() { return prUrl; }
    public void setPrUrl(String prUrl) { this.prUrl = prUrl; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public boolean isPublishAttempted() { return publishAttempted; }
    public void setPublishAttempted(boolean publishAttempted) { this.publishAttempted = publishAttempted; }
    public boolean isCancelRequested() { return cancelRequested; }
    public void setCancelRequested(boolean cancelRequested) { this.cancelRequested = cancelRequested; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getModelUsage() { return modelUsage; }
    public void setModelUsage(String modelUsage) { this.modelUsage = modelUsage; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
