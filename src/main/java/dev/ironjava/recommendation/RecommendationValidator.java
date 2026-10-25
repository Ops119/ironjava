package dev.ironjava.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class RecommendationValidator {
    private static final Pattern TEST_CLAIM = Pattern.compile(
            "(?i)(tests?\\s+(have\\s+)?passed|verification\\s+(has\\s+)?succeeded|all\\s+checks\\s+passed)");
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;

    public RecommendationValidator() {
        try (InputStream input = RecommendationValidator.class.getResourceAsStream("/schemas/recommendation-response.schema.json")) {
            if (input == null) {
                throw new IllegalStateException("recommendation schema missing");
            }
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("recommendation schema unreadable", exception);
        }
    }

    public record Recommendation(
            String findingId,
            String coordinate,
            String candidateVersion,
            String summary,
            List<String> compatibilityNotes,
            List<String> evidenceIds,
            String source
    ) {
    }

    public Recommendation validate(String json, String findingId, String coordinate, Set<String> candidates, Set<String> evidenceIds) {
        JsonNode node;
        try {
            node = mapper.readTree(stripFence(json));
        } catch (Exception exception) {
            throw new RecommendationRejectedException("MALFORMED_JSON");
        }
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            throw new RecommendationRejectedException("SCHEMA");
        }
        String responseFinding = node.get("findingId").asText();
        String responseCoordinate = node.get("coordinate").asText();
        String version = node.get("candidateVersion").asText();
        if (!findingId.equals(responseFinding) || !coordinate.equals(responseCoordinate)) {
            throw new RecommendationRejectedException("IDENTITY_MISMATCH");
        }
        if (!candidates.contains(version)) {
            throw new RecommendationRejectedException("VERSION_NOT_IN_CANDIDATE_LIST");
        }
        List<String> notes = new ArrayList<>();
        node.get("compatibilityNotes").forEach(item -> notes.add(item.asText()));
        List<String> evidence = new ArrayList<>();
        node.get("evidenceIds").forEach(item -> evidence.add(item.asText()));
        if (!evidenceIds.containsAll(evidence)) {
            throw new RecommendationRejectedException("UNKNOWN_EVIDENCE");
        }
        String summary = node.get("summary").asText();
        if (TEST_CLAIM.matcher(summary).find() || notes.stream().anyMatch(note -> TEST_CLAIM.matcher(note).find())) {
            throw new RecommendationRejectedException("TESTS_CLAIMED");
        }
        return new Recommendation(responseFinding, responseCoordinate, version, summary, notes, evidence, "MODEL");
    }

    public static String stripFence(String json) {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.startsWith("```")) {
            int first = trimmed.indexOf('\n');
            int last = trimmed.lastIndexOf("```");
            if (first > 0 && last > first) {
                return trimmed.substring(first + 1, last).trim();
            }
        }
        return trimmed;
    }

    public static final class RecommendationRejectedException extends RuntimeException {
        public RecommendationRejectedException(String code) {
            super(code);
        }
    }
}
