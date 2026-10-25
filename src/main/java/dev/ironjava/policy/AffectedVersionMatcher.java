package dev.ironjava.policy;

import java.util.List;

/**
 * OSV affected-version evaluation using Maven {@code ComparableVersion} ordering.
 * Follows the OSV schema {@code IncludedInRanges} / {@code IncludedInVersions} algorithm.
 * GIT ranges are not treated as a Maven version relationship.
 */
public final class AffectedVersionMatcher {
    public enum Status {
        AFFECTED, UNAFFECTED, UNSUPPORTED
    }

    public record RangeEvent(String introduced, String fixed, String lastAffected, String limit) {
    }

    public record VersionRange(String type, List<RangeEvent> events) {
    }

    public Status status(String version, List<VersionRange> ranges, List<String> explicitVersions) {
        if (explicitVersions != null) {
            for (String pinned : explicitVersions) {
                if (pinned != null && !pinned.isBlank() && MavenVersions.compare(version, pinned) == 0) {
                    return Status.AFFECTED;
                }
            }
        }
        boolean sawSupported = explicitVersions != null && explicitVersions.stream().anyMatch(v -> v != null && !v.isBlank());
        if (ranges == null || ranges.isEmpty()) {
            return sawSupported ? Status.UNAFFECTED : Status.UNSUPPORTED;
        }
        boolean anySupportedRange = false;
        for (VersionRange range : ranges) {
            if (range == null || range.type() == null) {
                return Status.UNSUPPORTED;
            }
            if ("GIT".equalsIgnoreCase(range.type())) {
                continue;
            }
            if (!"ECOSYSTEM".equalsIgnoreCase(range.type()) && !"SEMVER".equalsIgnoreCase(range.type())) {
                return Status.UNSUPPORTED;
            }
            anySupportedRange = true;
            if (includedInRange(version, range)) {
                return Status.AFFECTED;
            }
        }
        if (!anySupportedRange && !sawSupported) {
            return Status.UNSUPPORTED;
        }
        return Status.UNAFFECTED;
    }

    public boolean affected(String version, List<VersionRange> ranges, List<String> explicitVersions) {
        return status(version, ranges, explicitVersions) == Status.AFFECTED;
    }

    private boolean includedInRange(String version, VersionRange range) {
        if (!beforeLimits(version, range)) {
            return false;
        }
        boolean vulnerable = false;
        List<RangeEvent> events = range.events() == null ? List.of() : range.events();
        boolean introducedSeen = false;
        for (RangeEvent event : events) {
            if (event.introduced() != null) {
                introducedSeen = true;
                if (greaterOrEqual(version, event.introduced())) {
                    vulnerable = true;
                }
            } else if (event.fixed() != null) {
                if (greaterOrEqual(version, event.fixed())) {
                    vulnerable = false;
                }
            } else if (event.lastAffected() != null) {
                if (MavenVersions.compare(version, event.lastAffected()) > 0) {
                    vulnerable = false;
                }
            }
        }
        return introducedSeen && vulnerable;
    }

    private boolean beforeLimits(String version, VersionRange range) {
        boolean anyLimit = false;
        if (range.events() != null) {
            for (RangeEvent event : range.events()) {
                if (event.limit() != null) {
                    anyLimit = true;
                    if (MavenVersions.compare(version, event.limit()) < 0) {
                        return true;
                    }
                }
            }
        }
        return !anyLimit;
    }

    private boolean greaterOrEqual(String version, String boundary) {
        if ("0".equals(boundary)) {
            return true;
        }
        return MavenVersions.compare(version, boundary) >= 0;
    }
}
