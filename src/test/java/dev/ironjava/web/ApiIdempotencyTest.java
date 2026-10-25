package dev.ironjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ironjava.orchestrator.QueueService;
import dev.ironjava.persistence.FindingEntity;
import dev.ironjava.persistence.FindingRepository;
import dev.ironjava.persistence.ScanRunEntity;
import dev.ironjava.persistence.ScanRunRepository;
import dev.ironjava.verification.ProcessCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIdempotencyTest {
    private static Path repository;
    private static String baseSha;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/ironjava");
        registry.add("spring.datasource.username", () -> "ironjava");
        registry.add("spring.datasource.password", () -> "ironjava");
        repository = Files.createTempDirectory("ironjava-api-repo");
        Files.writeString(repository.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion></project>\n");
        git("init", "-b", "cursor/ironjava-acceptance-fixture-68a8");
        git("add", "pom.xml");
        git("-c", "user.email=test@example.com", "-c", "user.name=IronJava Test", "commit", "-m", "init");
        baseSha = git("rev-parse", "HEAD").trim();
        registry.add("ironjava.repositories[0].id", () -> "pilot-fixture");
        registry.add("ironjava.repositories[0].display-name", () -> "IronJava acceptance fixture");
        registry.add("ironjava.repositories[0].source", () -> "local");
        registry.add("ironjava.repositories[0].local-path", () -> repository.toString());
        registry.add("ironjava.repositories[0].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[0].github-repo", () -> "ironjava-fixture");
        registry.add("ironjava.repositories[0].allowed-branch", () -> "cursor/ironjava-acceptance-fixture-68a8");
        registry.add("ironjava.repositories[0].build-profile", () -> "verify");
        registry.add("ironjava.repositories[1].id", () -> "pilot-fixture-test-failure");
        registry.add("ironjava.repositories[1].source", () -> "local");
        registry.add("ironjava.repositories[1].local-path", () -> repository.toString());
        registry.add("ironjava.repositories[1].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[1].github-repo", () -> "ironjava-fixture");
        registry.add("ironjava.repositories[1].allowed-branch", () -> "scenario/test-failure");
        registry.add("ironjava.repositories[1].build-profile", () -> "verify");
        registry.add("ironjava.auth.password", () -> "test-password");
        registry.add("ironjava.auth.api-token", () -> "test-api-token");
        registry.add("ironjava.worker-enabled", () -> "false");
        registry.add("ironjava.control-loop-enabled", () -> "false");
        registry.add("ironjava.model.mode", () -> "disabled");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ScanRunRepository scans;
    @Autowired
    FindingRepository findings;
    @Autowired
    QueueService queue;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void resumeSharedQueue() {
        queue.setPaused(false, "test");
    }

    @Test
    void idempotencyAndStructuralRejections() throws Exception {
        String suffix = UUID.randomUUID().toString();
        mvc.perform(get("/dashboard")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/login").with(csrf()).param("username", "ironjava").param("password", "test-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/dashboard"));

        mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/scans/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "scan-unknown-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"nope\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "scan-extra-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture\",\"cloneUrl\":\"https://evil\"}"))
                .andExpect(status().isUnprocessableEntity());

        String scanKey = "scan-1-" + suffix;
        String first = mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", scanKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", scanKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        mvc.perform(post("/api/scans").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", scanKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"repositoryId\":\"pilot-fixture-test-failure\"}"))
                .andExpect(status().isConflict());

        UUID findingId = eligibleFinding();
        String body = "{\"findingId\":\"" + findingId + "\",\"candidateVersion\":\"1.10.0\"}";
        mvc.perform(post("/api/remediations").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "fix-1-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/remediations").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "fix-2-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        Files.writeString(repository.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><description>moved</description></project>\n");
        git("add", "pom.xml");
        git("-c", "user.email=test@example.com", "-c", "user.name=IronJava Test", "commit", "-m", "move");
        String stale = mvc.perform(post("/api/remediations").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "fix-stale-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertTrue(stale.contains("STALE_BASE"));
        mvc.perform(post("/api/queue").with(httpBasic("ironjava", "test-password")).with(csrf())
                        .header("Idempotency-Key", "queue-basic-" + suffix).contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/queue").header("Authorization", "Bearer test-api-token").header("Idempotency-Key", "queue-1-" + suffix)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paused\":true}"))
                .andExpect(status().isOk());
    }

    private UUID eligibleFinding() throws Exception {
        ScanRunEntity scan = new ScanRunEntity();
        scan.setId(UUID.randomUUID());
        scan.setRepositoryId("pilot-fixture");
        scan.setAllowedBranch("cursor/ironjava-acceptance-fixture-68a8");
        scan.setBaseSha(baseSha);
        scan.setState("COMPLETED");
        scan.setActor("test");
        scan.setCreatedAt(Instant.now());
        scan.setUpdatedAt(Instant.now());
        scans.save(scan);
        FindingEntity finding = new FindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setScanRunId(scan.getId());
        finding.setRepositoryId("pilot-fixture");
        finding.setBaseSha(baseSha);
        finding.setCoordinate("org.apache.commons:commons-text");
        finding.setGroupId("org.apache.commons");
        finding.setArtifactId("commons-text");
        finding.setVersion("1.9");
        finding.setScope("compile");
        finding.setDirectDependency(true);
        finding.setPomPath("pom.xml");
        finding.setVersionSource("EXPLICIT");
        finding.setAdvisoryIds("[\"GHSA-599f-7c49-w659\"]");
        finding.setPrimaryAdvisoryId("GHSA-599f-7c49-w659");
        finding.setAliases("[\"CVE-2022-42889\"]");
        finding.setSeverity("9.8");
        finding.setAffectedRanges("[]");
        finding.setEligibility("ELIGIBLE");
        finding.setEligibilityReasons("[]");
        finding.setCandidates("[\"1.10.0\"]");
        finding.setCreatedAt(Instant.now());
        findings.save(finding);
        JsonNode node = mapper.readTree(mvc.perform(get("/api/scans/" + scan.getId() + "/findings")
                        .header("Authorization", "Bearer test-api-token"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals("ELIGIBLE", node.get(0).get("eligibility").asText());
        return finding.getId();
    }

    private static String git(String... args) {
        var command = new java.util.ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(args));
        var result = ProcessCommand.run(command, repository, Duration.ofSeconds(20), () -> false, 4000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(result.output());
        }
        return result.output();
    }
}
