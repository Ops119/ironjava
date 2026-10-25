package dev.ironjava.policy;

import dev.ironjava.domain.Domain.Eligibility;
import dev.ironjava.domain.Domain.VersionSource;

import java.util.ArrayList;
import java.util.List;

public final class EligibilityPolicy {
    private final AffectedVersionMatcher matcher = new AffectedVersionMatcher();

    public record AdvisoryFact(
            String primaryId,
            List<String> ids,
            List<String> aliases,
            String severity,
            List<AffectedVersionMatcher.VersionRange> ranges,
            List<String> explicitVersions,
            String summary
    ) {
    }

    public record DependencyFact(
            String groupId,
            String artifactId,
            String version,
            String scope,
            boolean direct,
            boolean rootPom,
            VersionSource versionSource,
            boolean excluded,
            int matchingDirectDeclarations
    ) {
        public String coordinate() {
            return groupId + ":" + artifactId;
        }
    }

    public record Decision(
            Eligibility eligibility,
            List<String> reasons,
            String severityDisplay,
            List<String> candidates
    ) {
    }

    public Decision decide(DependencyFact dependency, AdvisoryFact advisory, boolean baselinePassed, boolean metadataReachable,
                           List<String> publishedVersions) {
        List<String> reasons = new ArrayList<>();
        String severity = advisory == null || advisory.severity() == null || advisory.severity().isBlank()
                ? "Unknown"
                : advisory.severity();
        if (dependency == null || advisory == null) {
            reasons.add("MISSING_FACTS");
            return new Decision(Eligibility.INELIGIBLE, reasons, severity, List.of());
        }
        if (!dependency.direct() || !dependency.rootPom()) {
            reasons.add(dependency.rootPom() ? "NOT_DIRECT" : "NOT_ROOT_POM");
        }
        String scope = dependency.scope() == null || dependency.scope().isBlank() ? "compile" : dependency.scope();
        if (!"compile".equals(scope) && !"runtime".equals(scope)) {
            reasons.add("NOT_PRODUCTION_SCOPE");
        }
        if (dependency.versionSource() == VersionSource.PROPERTY) {
            reasons.add("UNSUPPORTED_VERSION_SOURCE");
        } else if (dependency.versionSource() == VersionSource.MANAGED || dependency.versionSource() == VersionSource.UNKNOWN
                || dependency.version() == null || dependency.version().isBlank()) {
            reasons.add("BOM_OR_PARENT_MANAGED");
        }
        if (dependency.matchingDirectDeclarations() > 1) {
            reasons.add("UNCERTAIN_VERSION_OWNERSHIP");
        }
        if (dependency.excluded()) {
            reasons.add("EXCLUDED_BY_POLICY");
        }
        if (advisory.primaryId() == null || advisory.primaryId().isBlank()) {
            reasons.add("NO_ADVISORY_ID");
        }
        AffectedVersionMatcher.Status currentStatus = matcher.status(
                dependency.version() == null ? "" : dependency.version(),
                advisory.ranges(),
                advisory.explicitVersions());
        if (currentStatus == AffectedVersionMatcher.Status.UNSUPPORTED) {
            reasons.add("NO_AFFECTED_RELATIONSHIP");
        } else if (currentStatus == AffectedVersionMatcher.Status.UNAFFECTED) {
            reasons.add("CURRENT_VERSION_NOT_AFFECTED");
        }
        if (!baselinePassed) {
            reasons.add("BASELINE_NOT_PASSED");
        }
        boolean blocked = reasons.stream().anyMatch(reason -> !reason.equals("BASELINE_NOT_PASSED"));
        if (blocked) {
            Eligibility eligibility = manual(reasons) ? Eligibility.MANUAL_REVIEW : Eligibility.INELIGIBLE;
            return new Decision(eligibility, List.copyOf(reasons), severity, List.of());
        }
        if (!metadataReachable) {
            reasons.add("METADATA_UNAVAILABLE");
            return new Decision(Eligibility.MANUAL_REVIEW, List.copyOf(reasons), severity, List.of());
        }
        List<String> candidates = candidates(dependency.version(), advisory, publishedVersions);
        if (candidates.isEmpty()) {
            if (higherMajorFixExists(dependency.version(), advisory, publishedVersions)) {
                reasons.add("MAJOR_UPGRADE_REQUIRED");
                return new Decision(Eligibility.MANUAL_REVIEW, List.copyOf(reasons), severity, List.of());
            }
            reasons.add("NO_ELIGIBLE_CANDIDATE");
            return new Decision(Eligibility.MANUAL_REVIEW, List.copyOf(reasons), severity, List.of());
        }
        if (!baselinePassed) {
            return new Decision(Eligibility.INELIGIBLE, List.copyOf(reasons), severity, candidates);
        }
        return new Decision(Eligibility.ELIGIBLE, List.of(), severity, candidates);
    }

    private boolean manual(List<String> reasons) {
        return reasons.contains("UNSUPPORTED_VERSION_SOURCE")
                || reasons.contains("BOM_OR_PARENT_MANAGED")
                || reasons.contains("UNCERTAIN_VERSION_OWNERSHIP")
                || reasons.contains("MAJOR_UPGRADE_REQUIRED")
                || reasons.contains("NO_ELIGIBLE_CANDIDATE")
                || reasons.contains("METADATA_UNAVAILABLE")
                || reasons.contains("NOT_PRODUCTION_SCOPE")
                || reasons.contains("NOT_DIRECT")
                || reasons.contains("NOT_ROOT_POM");
    }

    public List<String> candidates(String current, AdvisoryFact advisory, List<String> publishedVersions) {
        List<String> safe = new ArrayList<>();
        if (publishedVersions == null) {
            return List.of();
        }
        for (String version : publishedVersions) {
            if (version == null || version.isBlank()) {
                continue;
            }
            if (MavenVersions.compare(version, current) <= 0) {
                continue;
            }
            if (!MavenVersions.sameMajor(version, current)) {
                continue;
            }
            if (matcher.status(version, advisory.ranges(), advisory.explicitVersions()) != AffectedVersionMatcher.Status.UNAFFECTED) {
                continue;
            }
            safe.add(version);
        }
        safe.sort(MavenVersions::compare);
        if (safe.size() > 3) {
            return List.copyOf(safe.subList(0, 3));
        }
        return List.copyOf(safe);
    }

    private boolean higherMajorFixExists(String current, AdvisoryFact advisory, List<String> publishedVersions) {
        if (publishedVersions == null) {
            return false;
        }
        for (String version : publishedVersions) {
            if (version == null || version.isBlank() || MavenVersions.sameMajor(version, current)) {
                continue;
            }
            if (MavenVersions.compare(version, current) > 0
                    && matcher.status(version, advisory.ranges(), advisory.explicitVersions()) == AffectedVersionMatcher.Status.UNAFFECTED) {
                return true;
            }
        }
        return false;
    }
}
