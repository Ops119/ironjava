package dev.ironjava.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class OsvReportParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public record Advisory(
            String coordinate,
            String version,
            String primaryId,
            List<String> ids,
            List<String> aliases,
            String severity,
            List<dev.ironjava.policy.AffectedVersionMatcher.VersionRange> ranges,
            List<String> explicitVersions,
            String summary,
            String sourceType,
            List<String> dependencyGroups
    ) {
    }

    public record Parsed(List<Advisory> advisories, boolean partial) {
    }

    public Parsed parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new ScannerParseException("MALFORMED_JSON");
            }
            boolean partial = root.hasNonNull("errors") && root.get("errors").isArray() && !root.get("errors").isEmpty();
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                throw new ScannerParseException("MALFORMED_JSON");
            }
            List<Advisory> advisories = new ArrayList<>();
            for (JsonNode result : results) {
                JsonNode packages = result.get("packages");
                if (packages == null || !packages.isArray()) {
                    continue;
                }
                String sourceType = text(result.path("source"), "type");
                String sourcePath = text(result.path("source"), "path");
                for (JsonNode pkg : packages) {
                    JsonNode identity = pkg.get("package");
                    if (identity == null || !"Maven".equals(text(identity, "ecosystem"))) {
                        continue;
                    }
                    String name = text(identity, "name");
                    String version = text(identity, "version");
                    if (name == null || version == null || version.isBlank()) {
                        continue;
                    }
                    List<String> dependencyGroups = strings(pkg.get("dependency_groups"));
                    JsonNode vulnerabilities = pkg.get("vulnerabilities");
                    JsonNode groups = pkg.get("groups");
                    if (groups != null && groups.isArray() && !groups.isEmpty()) {
                        for (JsonNode group : groups) {
                            advisories.add(advisory(name, version, group, vulnerabilities, sourceType, sourcePath, dependencyGroups));
                        }
                    } else if (vulnerabilities != null && vulnerabilities.isArray()) {
                        for (JsonNode vulnerability : vulnerabilities) {
                            advisories.add(single(name, version, vulnerability, sourceType, sourcePath, dependencyGroups));
                        }
                    }
                }
            }
            return new Parsed(advisories, partial);
        } catch (ScannerParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ScannerParseException("MALFORMED_JSON");
        }
    }

    private Advisory advisory(String name, String version, JsonNode group, JsonNode vulnerabilities,
                               String sourceType, String sourcePath, List<String> dependencyGroups) {
        List<String> ids = strings(group.get("ids"));
        Set<String> aliases = new LinkedHashSet<>(strings(group.get("aliases")));
        List<JsonNode> matched = new ArrayList<>();
        if (vulnerabilities != null && vulnerabilities.isArray()) {
            for (JsonNode vulnerability : vulnerabilities) {
                String id = text(vulnerability, "id");
                if (id != null && ids.contains(id)) {
                    matched.add(vulnerability);
                    aliases.addAll(strings(vulnerability.get("aliases")));
                    if (id != null) {
                        aliases.add(id);
                    }
                }
            }
        }
        String severity = text(group, "max_severity");
        if (severity == null) {
            severity = firstSeverity(matched);
        }
        return build(name, version, ids, new ArrayList<>(aliases), severity, matched, sourceType, sourcePath, dependencyGroups);
    }

    private Advisory single(String name, String version, JsonNode vulnerability, String sourceType, String sourcePath,
                            List<String> dependencyGroups) {
        String id = text(vulnerability, "id");
        List<String> ids = id == null ? List.of() : List.of(id);
        Set<String> aliases = new LinkedHashSet<>(strings(vulnerability.get("aliases")));
        if (id != null) {
            aliases.add(id);
        }
        return build(name, version, ids, new ArrayList<>(aliases), firstSeverity(List.of(vulnerability)),
                List.of(vulnerability), sourceType, sourcePath, dependencyGroups);
    }

    private Advisory build(String name, String version, List<String> ids, List<String> aliases, String severity,
                           List<JsonNode> vulnerabilities, String sourceType, String sourcePath,
                           List<String> dependencyGroups) {
        List<dev.ironjava.policy.AffectedVersionMatcher.VersionRange> ranges = new ArrayList<>();
        List<String> explicit = new ArrayList<>();
        String summary = null;
        for (JsonNode vulnerability : vulnerabilities) {
            if (summary == null) {
                summary = text(vulnerability, "summary");
            }
            JsonNode affected = vulnerability.get("affected");
            if (affected == null || !affected.isArray()) {
                continue;
            }
            for (JsonNode item : affected) {
                JsonNode pkg = item.get("package");
                if (pkg != null && pkg.hasNonNull("name") && !name.equals(text(pkg, "name"))) {
                    continue;
                }
                if (pkg != null && pkg.hasNonNull("ecosystem") && !"Maven".equals(text(pkg, "ecosystem"))) {
                    continue;
                }
                explicit.addAll(strings(item.get("versions")));
                JsonNode rangeNodes = item.get("ranges");
                if (rangeNodes == null || !rangeNodes.isArray()) {
                    continue;
                }
                for (JsonNode range : rangeNodes) {
                    List<dev.ironjava.policy.AffectedVersionMatcher.RangeEvent> events = new ArrayList<>();
                    JsonNode eventNodes = range.get("events");
                    if (eventNodes != null && eventNodes.isArray()) {
                        for (JsonNode event : eventNodes) {
                            events.add(new dev.ironjava.policy.AffectedVersionMatcher.RangeEvent(
                                    text(event, "introduced"),
                                    text(event, "fixed"),
                                    text(event, "last_affected"),
                                    text(event, "limit")));
                        }
                    }
                    ranges.add(new dev.ironjava.policy.AffectedVersionMatcher.VersionRange(text(range, "type"), events));
                }
            }
        }
        boolean rootPom = sourcePath == null || sourcePath.endsWith("/pom.xml") || sourcePath.endsWith("\\pom.xml") || "pom.xml".equals(sourcePath);
        return new Advisory(name, version, primary(ids), ids, aliases, severity, ranges, explicit, summary,
                rootPom ? sourceType : "nested:" + sourceType, dependencyGroups == null ? List.of() : dependencyGroups);
    }

    public static String primary(List<String> ids) {
        if (ids == null) {
            return null;
        }
        for (String id : ids) {
            if (id != null && id.startsWith("GHSA-")) {
                return id;
            }
        }
        for (String id : ids) {
            if (id != null && id.startsWith("CVE-")) {
                return id;
            }
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String firstSeverity(List<JsonNode> vulnerabilities) {
        for (JsonNode vulnerability : vulnerabilities) {
            JsonNode severity = vulnerability.get("severity");
            if (severity != null && severity.isArray()) {
                for (JsonNode item : severity) {
                    String score = text(item, "score");
                    if (score != null && !score.isBlank()) {
                        return score;
                    }
                }
            }
        }
        return null;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    values.add(item.asText());
                }
            }
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode value = node.get(field);
        return value.isTextual() || value.isNumber() ? value.asText() : null;
    }

    public static final class ScannerParseException extends RuntimeException {
        public ScannerParseException(String message) {
            super(message);
        }
    }
}
