package dev.ironjava.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ironjava.orchestrator.QueueService;
import dev.ironjava.orchestrator.RemediationPipeline;
import dev.ironjava.orchestrator.ScanPipeline;
import dev.ironjava.persistence.FindingEntity;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.RemediationRepository;
import dev.ironjava.persistence.ScanRunRepository;
import dev.ironjava.verification.ProcessCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "IRONJAVA_LIVE", matches = "true")
@SpringBootTest
class LiveFixtureIT {
    private static final Path ACCEPTANCE = Path.of("/tmp/ironjava-live/acceptance");
    private static final Path FAILURE = Path.of("/tmp/ironjava-live/test-failure");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        clone("cursor/ironjava-acceptance-fixture-68a8", ACCEPTANCE);
        clone("scenario/test-failure", FAILURE);
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/ironjava");
        registry.add("spring.datasource.username", () -> "ironjava");
        registry.add("spring.datasource.password", () -> "ironjava");
        registry.add("ironjava.auth.password", () -> "test-password");
        registry.add("ironjava.auth.api-token", () -> "test-api-token");
        registry.add("ironjava.role", () -> "ALL");
        registry.add("ironjava.worker-enabled", () -> "true");
        registry.add("ironjava.control-loop-enabled", () -> "true");
        registry.add("ironjava.model.mode", () -> "disabled");
        registry.add("ironjava.runner.mode", () -> "process");
        registry.add("ironjava.github.app-id", () -> "");
        registry.add("ironjava.github.private-key-path", () -> "");
        registry.add("ironjava.repositories[0].id", () -> "pilot-fixture");
        registry.add("ironjava.repositories[0].source", () -> "local");
        registry.add("ironjava.repositories[0].local-path", ACCEPTANCE::toString);
        registry.add("ironjava.repositories[0].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[0].github-repo", () -> "ironjava-fixture");
        registry.add("ironjava.repositories[0].allowed-branch", () -> "cursor/ironjava-acceptance-fixture-68a8");
        registry.add("ironjava.repositories[0].build-profile", () -> "verify");
        registry.add("ironjava.repositories[0].installation-id", () -> "0");
        registry.add("ironjava.repositories[1].id", () -> "pilot-fixture-test-failure");
        registry.add("ironjava.repositories[1].source", () -> "local");
        registry.add("ironjava.repositories[1].local-path", FAILURE::toString);
        registry.add("ironjava.repositories[1].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[1].github-repo", () -> "ironjava-fixture");
        registry.add("ironjava.repositories[1].allowed-branch", () -> "scenario/test-failure");
        registry.add("ironjava.repositories[1].build-profile", () -> "verify");
        registry.add("ironjava.repositories[1].installation-id", () -> "0");
        String maven = System.getenv().getOrDefault("IRONJAVA_MAVEN_BINARY", "mvn");
        registry.add("ironjava.runner.maven-binary", () -> maven);
        registry.add("ironjava.limits.stage-timeout-seconds", () -> "1800");
        registry.add("ironjava.limits.lease-seconds", () -> "120");
    }

    @Autowired
    ScanPipeline scans;
    @Autowired
    RemediationPipeline remediations;
    @Autowired
    ScanRunRepository scanRepository;
    @Autowired
    FindingRepository findings;
    @Autowired
    RemediationRepository remediationRepository;
    @Autowired
    QueueService queue;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptanceFixtureStopsBeforePublication() throws Exception {
        queue.setPaused(false, "live");
        UUID scanId = scans.create("live", "pilot-fixture", "live-scan-" + UUID.randomUUID());
        var scan = waitForTerminalScan(scanId);
        assertEquals("COMPLETED", scan.getState(), scan.getReasonMessage());
        List<FindingEntity> rows = findings.findByScanRunIdOrderByCoordinateAsc(scanId);
        FindingEntity text = rows.stream().filter(row -> row.getCoordinate().equals("org.apache.commons:commons-text")).findFirst().orElseThrow();
        assertEquals("ELIGIBLE", text.getEligibility());
        assertTrue(text.getCandidates().contains("1.10.0"));
        assertTrue(rows.stream().anyMatch(row -> row.getCoordinate().contains("snakeyaml") && row.getEligibility().equals("MANUAL_REVIEW") && row.getEligibilityReasons().contains("MAJOR_UPGRADE_REQUIRED")));
        assertTrue(rows.stream().anyMatch(row -> row.getCoordinate().contains("guava") && row.getEligibility().equals("MANUAL_REVIEW") && row.getEligibilityReasons().contains("BOM_OR_PARENT_MANAGED")));
        assertTrue(rows.stream().anyMatch(row -> row.getCoordinate().contains("gson") && row.getEligibility().equals("MANUAL_REVIEW") && row.getEligibilityReasons().contains("NOT_PRODUCTION_SCOPE")));
        var created = remediations.create("live", text.getId(), "1.10.0", "live-fix-" + UUID.randomUUID());
        var run = waitForTerminalRemediation(created.id());
        assertEquals("BLOCKED", run.getState(), run.getReasonMessage());
        assertEquals("PUBLICATION_NOT_CONFIGURED", run.getReasonCode());
        assertTrue(run.getPrUrl() == null || run.getPrUrl().isBlank());
        assertFalse(run.isPublishAttempted());
        JsonNode evidence = mapper.readTree(run.getVerification());
        assertEquals("process", evidence.path("isolation").asText());
        assertEquals("SERVER_POLICY", evidence.path("recommendationSource").asText());
        assertEquals(17, evidence.path("tests").path("testsRun").asInt());
        assertEquals(0, evidence.path("tests").path("failures").asInt());
        assertTrue(evidence.path("patchHash").asText().length() > 10);
        assertTrue(run.getPatchDiff().contains("1.10.0"));
        assertTrue(run.getPatchDiff().contains("commons-text"));
    }

    @Test
    void testFailureBranchDoesNotPublish() throws Exception {
        queue.setPaused(false, "live");
        UUID scanId = scans.create("live", "pilot-fixture-test-failure", "live-fail-scan-" + UUID.randomUUID());
        var scan = waitForTerminalScan(scanId);
        assertEquals("COMPLETED", scan.getState(), scan.getReasonMessage());
        FindingEntity text = findings.findByScanRunIdOrderByCoordinateAsc(scanId).stream()
                .filter(row -> "ELIGIBLE".equals(row.getEligibility()) && row.getCoordinate().contains("commons-text"))
                .findFirst().orElseThrow();
        var created = remediations.create("live", text.getId(), "1.10.0", "live-fail-fix-" + UUID.randomUUID());
        var run = waitForTerminalRemediation(created.id());
        assertEquals("FAILED", run.getState(), run.getReasonMessage());
        assertEquals("TESTS_FAILED", run.getReasonCode());
        assertTrue(run.getPrUrl() == null || run.getPrUrl().isBlank());
    }

    private dev.ironjava.persistence.ScanRunEntity waitForTerminalScan(UUID id) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            var scan = scanRepository.findById(id).orElseThrow();
            if (dev.ironjava.domain.Domain.RunState.valueOf(scan.getState()).terminal()) {
                return scan;
            }
            Thread.sleep(2000);
        }
        return scanRepository.findById(id).orElseThrow();
    }

    private dev.ironjava.persistence.RemediationRunEntity waitForTerminalRemediation(UUID id) throws InterruptedException {
        for (int i = 0; i < 600; i++) {
            var run = remediationRepository.findById(id).orElseThrow();
            if (dev.ironjava.domain.Domain.RunState.valueOf(run.getState()).terminal()) {
                return run;
            }
            Thread.sleep(2000);
        }
        return remediationRepository.findById(id).orElseThrow();
    }

    private static void clone(String branch, Path dest) throws Exception {
        if (Files.isRegularFile(dest.resolve("pom.xml")) && Files.isDirectory(dest.resolve(".git"))) {
            return;
        }
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest)) {
            ProcessCommand.run(List.of("rm", "-rf", dest.toString()), Path.of("/tmp"), Duration.ofSeconds(30), () -> false, 1000);
        }
        var result = ProcessCommand.run(List.of(
                "git", "clone", "--depth", "1", "--branch", branch,
                "https://github.com/Ops119/ironjava-fixture.git", dest.toString()),
                Path.of("/tmp"), Duration.ofMinutes(2), () -> false, 20000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(result.output());
        }
    }
}
