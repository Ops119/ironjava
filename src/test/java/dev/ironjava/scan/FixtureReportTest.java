package dev.ironjava.scan;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixtureReportTest {
    @Test
    void baselineFixtureHasFiveAdvisoriesAndTheDocumentedShapes() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/osv-baseline.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var parsed = new OsvReportParser().parse(json);
        assertEquals(5, parsed.advisories().size());
        var gson = parsed.advisories().stream().filter(item -> item.coordinate().contains("gson")).findFirst().orElseThrow();
        assertTrue(gson.dependencyGroups().contains("test"));
        assertTrue(gson.aliases().contains("CVE-2022-25647"));
        var text = parsed.advisories().stream()
                .filter(item -> item.coordinate().equals("org.apache.commons:commons-text") && item.primaryId().startsWith("GHSA"))
                .findFirst().orElseThrow();
        assertEquals("1.9", text.version());
        assertEquals("GHSA-599f-7c49-w659", text.primaryId());
        assertTrue(text.aliases().contains("CVE-2022-42889"));
        var snake = parsed.advisories().stream().filter(item -> item.coordinate().contains("snakeyaml")).findFirst().orElseThrow();
        assertEquals("1.33", snake.version());
        var guava = parsed.advisories().stream().filter(item -> item.coordinate().contains("guava")).toList();
        assertEquals(2, guava.size());
        assertTrue(guava.stream().allMatch(item -> "unknown".equals(item.sourceType())));
        assertTrue(guava.stream().anyMatch(item -> "GHSA-7g45-4rm6-3mm3".equals(item.primaryId())));
        assertTrue(guava.stream().anyMatch(item -> "GHSA-5mg8-w23w-74h3".equals(item.primaryId())));
    }
}
