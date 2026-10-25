package dev.ironjava.audit;

import dev.ironjava.gateway.TarExtractor;
import dev.ironjava.recommendation.RecommendationValidator;
import dev.ironjava.verification.ProcessCommand;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionAndProcessTest {
    @Test
    void redactsTokensAndScrubsChildEnvironment() {
        String redacted = LogRedactor.redact("token=ghp_abcdefghijklmnopqrstuvwxyz password: hunter2 Authorization: Bearer abc.def");
        assertFalse(redacted.contains("ghp_"));
        assertFalse(redacted.contains("hunter2"));
        assertFalse(redacted.contains("abc.def"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("GITHUB_TOKEN", "secret");
        env.put("IRONJAVA_AUTH_PASSWORD", "secret");
        env.put("IRONJAVA_MODEL_API_KEY", "secret");
        ProcessCommand.scrub(env);
        assertEquals(Set.of("PATH"), env.keySet());
    }

    @Test
    void processLogKeepsTheTrailingTestSummary(@TempDir Path dir) {
        ProcessCommand.Result result = ProcessCommand.run(List.of(
                "python3", "-c",
                "print('A'*400); print('Tests run: 17, Failures: 0, Errors: 0, Skipped: 0')"),
                dir, Duration.ofSeconds(20), () -> false, 80);
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Tests run: 17, Failures: 0, Errors: 0, Skipped: 0"));
        assertTrue(result.output().length() <= 80);
    }

    @Test
    void recommendationSchemaRejectsInventedVersionsAndTestClaims() {
        var validator = new RecommendationValidator();
        String ok = """
                {"findingId":"f1","coordinate":"org.apache.commons:commons-text","candidateVersion":"1.10.0","summary":"Server policy selected the minimum permitted candidate.","compatibilityNotes":["Review the release notes."],"evidenceIds":["baseline-scan"]}
                """;
        var recommendation = validator.validate(ok, "f1", "org.apache.commons:commons-text", Set.of("1.10.0"), Set.of("baseline-scan"));
        assertEquals("1.10.0", recommendation.candidateVersion());
        String invented = ok.replace("1.10.0", "9.9.9");
        assertThrows(RecommendationValidator.RecommendationRejectedException.class,
                () -> validator.validate(invented, "f1", "org.apache.commons:commons-text", Set.of("1.10.0"), Set.of("baseline-scan")));
        String claimed = ok.replace("Server policy selected the minimum permitted candidate.", "tests passed");
        assertThrows(RecommendationValidator.RecommendationRejectedException.class,
                () -> validator.validate(claimed, "f1", "org.apache.commons:commons-text", Set.of("1.10.0"), Set.of("baseline-scan")));
    }

    @Test
    void tarExtractorRefusesPathEscape(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var gzip = new GzipCompressorOutputStream(bytes);
             var tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry entry = new TarArchiveEntry("top/../../etc/passwd");
            byte[] payload = "nope".getBytes(StandardCharsets.UTF_8);
            entry.setSize(payload.length);
            tar.putArchiveEntry(entry);
            tar.write(payload);
            tar.closeArchiveEntry();
        }
        assertThrows(Exception.class, () -> TarExtractor.extractStrippingTopDirectory(bytes.toByteArray(), dir));
        assertTrue(Files.list(dir).findAny().isEmpty());
    }
}
