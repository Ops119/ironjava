package dev.ironjava.policy;

import dev.ironjava.domain.Domain.VersionSource;
import dev.ironjava.scan.OsvReportParser;

import java.util.List;
import java.util.Locale;

public final class FindingNormalizer {
    private FindingNormalizer() {
    }

    public static EligibilityPolicy.DependencyFact toFact(List<PomInspector.DirectDependency> direct,
                                                          OsvReportParser.Advisory advisory,
                                                          boolean excluded) {
        String[] parts = advisory.coordinate().split(":", 2);
        String groupId = parts[0];
        String artifactId = parts.length > 1 ? parts[1] : "";
        List<PomInspector.DirectDependency> matches = direct.stream()
                .filter(item -> groupId.equals(item.groupId()) && artifactId.equals(item.artifactId()))
                .toList();
        PomInspector.DirectDependency pom = matches.stream()
                .filter(item -> item.version() != null && item.version().equals(advisory.version()))
                .findFirst()
                .orElse(matches.size() == 1 ? matches.get(0) : null);
        boolean nonProduction = advisory.dependencyGroups() != null && advisory.dependencyGroups().stream()
                .map(group -> group.toLowerCase(Locale.ROOT))
                .anyMatch(group -> group.equals("test") || group.equals("dev") || group.equals("development"));
        boolean rootPom = advisory.sourceType() == null || !advisory.sourceType().startsWith("nested:");
        if (pom == null) {
            return new EligibilityPolicy.DependencyFact(groupId, artifactId, advisory.version(),
                    nonProduction ? "test" : "compile", false, rootPom, VersionSource.UNKNOWN, excluded, matches.size());
        }
        VersionSource source = pom.versionSource();
        String version = source == VersionSource.EXPLICIT ? pom.version() : advisory.version();
        String sourceType = advisory.sourceType() == null ? "" : advisory.sourceType();
        if ("unknown".equals(sourceType) && (pom.version() == null || !pom.version().equals(advisory.version()))) {
            source = VersionSource.MANAGED;
            version = advisory.version();
        }
        String scope = nonProduction ? "test" : pom.scope();
        return new EligibilityPolicy.DependencyFact(groupId, artifactId, version, scope, true, rootPom, source, excluded, matches.size());
    }
}
