package dev.ironjava.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "finding")
public class FindingEntity {
    @Id
    private UUID id;
    @Column(name = "scan_run_id", nullable = false)
    private UUID scanRunId;
    @Column(name = "repository_id", nullable = false)
    private String repositoryId;
    @Column(name = "base_sha", nullable = false)
    private String baseSha;
    @Column(nullable = false)
    private String coordinate;
    @Column(name = "group_id", nullable = false)
    private String groupId;
    @Column(name = "artifact_id", nullable = false)
    private String artifactId;
    @Column(nullable = false)
    private String version;
    @Column(nullable = false)
    private String scope;
    @Column(name = "direct_dependency", nullable = false)
    private boolean directDependency;
    @Column(name = "pom_path", nullable = false)
    private String pomPath;
    @Column(name = "version_source", nullable = false)
    private String versionSource;
    @Column(name = "advisory_ids", nullable = false)
    private String advisoryIds;
    @Column(name = "primary_advisory_id")
    private String primaryAdvisoryId;
    @Column(nullable = false)
    private String aliases;
    @Column(nullable = false)
    private String severity;
    @Column(name = "affected_ranges", nullable = false)
    private String affectedRanges;
    private String summary;
    @Column(nullable = false)
    private String eligibility;
    @Column(name = "eligibility_reasons", nullable = false)
    private String eligibilityReasons;
    @Column(nullable = false)
    private String candidates;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getScanRunId() { return scanRunId; }
    public void setScanRunId(UUID scanRunId) { this.scanRunId = scanRunId; }
    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }
    public String getBaseSha() { return baseSha; }
    public void setBaseSha(String baseSha) { this.baseSha = baseSha; }
    public String getCoordinate() { return coordinate; }
    public void setCoordinate(String coordinate) { this.coordinate = coordinate; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public boolean isDirectDependency() { return directDependency; }
    public void setDirectDependency(boolean directDependency) { this.directDependency = directDependency; }
    public String getPomPath() { return pomPath; }
    public void setPomPath(String pomPath) { this.pomPath = pomPath; }
    public String getVersionSource() { return versionSource; }
    public void setVersionSource(String versionSource) { this.versionSource = versionSource; }
    public String getAdvisoryIds() { return advisoryIds; }
    public void setAdvisoryIds(String advisoryIds) { this.advisoryIds = advisoryIds; }
    public String getPrimaryAdvisoryId() { return primaryAdvisoryId; }
    public void setPrimaryAdvisoryId(String primaryAdvisoryId) { this.primaryAdvisoryId = primaryAdvisoryId; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getAffectedRanges() { return affectedRanges; }
    public void setAffectedRanges(String affectedRanges) { this.affectedRanges = affectedRanges; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getEligibility() { return eligibility; }
    public void setEligibility(String eligibility) { this.eligibility = eligibility; }
    public String getEligibilityReasons() { return eligibilityReasons; }
    public void setEligibilityReasons(String eligibilityReasons) { this.eligibilityReasons = eligibilityReasons; }
    public String getCandidates() { return candidates; }
    public void setCandidates(String candidates) { this.candidates = candidates; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
