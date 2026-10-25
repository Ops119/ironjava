package dev.ironjava.orchestrator;

import dev.ironjava.gateway.RepositoryAccessException;
import dev.ironjava.gateway.RepositoryGateway;
import dev.ironjava.gateway.SourceSnapshot;
import dev.ironjava.verification.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class DistinctOutcomeTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5432/ironjava");
        registry.add("spring.datasource.username", () -> "ironjava");
        registry.add("spring.datasource.password", () -> "ironjava");
        registry.add("ironjava.auth.password", () -> "test-password");
        registry.add("ironjava.auth.api-token", () -> "test-api-token");
        registry.add("ironjava.worker-enabled", () -> "false");
        registry.add("ironjava.control-loop-enabled", () -> "false");
        registry.add("ironjava.model.mode", () -> "disabled");
        registry.add("ironjava.repositories[0].id", () -> "pilot-fixture");
        registry.add("ironjava.repositories[0].source", () -> "local");
        registry.add("ironjava.repositories[0].local-path", () -> "/tmp");
        registry.add("ironjava.repositories[0].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[0].github-repo", () -> "ironjava-fixture");
        registry.add("ironjava.repositories[0].allowed-branch", () -> "cursor/ironjava-acceptance-fixture-68a8");
        registry.add("ironjava.repositories[0].build-profile", () -> "verify");
        registry.add("ironjava.repositories[1].id", () -> "pilot-fixture-test-failure");
        registry.add("ironjava.repositories[1].source", () -> "local");
        registry.add("ironjava.repositories[1].local-path", () -> "/tmp");
        registry.add("ironjava.repositories[1].allowed-branch", () -> "scenario/test-failure");
        registry.add("ironjava.repositories[1].build-profile", () -> "verify");
        registry.add("ironjava.repositories[1].github-owner", () -> "Ops119");
        registry.add("ironjava.repositories[1].github-repo", () -> "ironjava-fixture");
    }

    @Autowired
    ScanPipeline scans;
    @Autowired
    QueueService queue;
    @Autowired
    dev.ironjava.persistence.ScanRunRepository repository;
    @MockitoBean
    RepositoryGateway gateway;
    @MockitoBean
    CommandRunner runner;

    @Test
    void inaccessibleRepositoryDoesNotScan(@TempDir Path dir) {
        when(gateway.resolveBaseSha(any())).thenThrow(new RepositoryAccessException("missing"));
        queue.setPaused(false, "test");
        var id = scans.create("test", "pilot-fixture", "inaccessible-" + UUID.randomUUID());
        scans.run(id);
        assertEquals("FAILED", repository.findById(id).orElseThrow().getState());
        assertEquals("REPOSITORY_INACCESSIBLE", repository.findById(id).orElseThrow().getReasonCode());
        verify(runner, never()).verify(any(), any(), any());
    }

    @Test
    void baselineFailureDoesNotScan(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        when(gateway.resolveBaseSha(any())).thenReturn("abc123");
        when(gateway.fetch(any(), any())).thenReturn(new SourceSnapshot("abc123", dir));
        when(runner.verify(any(), any(), any())).thenReturn(execution(1, "BUILD FAILURE\n"));
        queue.setPaused(false, "test");
        var id = scans.create("test", "pilot-fixture", "baseline-" + UUID.randomUUID());
        scans.run(id);
        assertEquals("FAILED", repository.findById(id).orElseThrow().getState());
        assertEquals("BASELINE_FAILED", repository.findById(id).orElseThrow().getReasonCode());
        verify(runner, never()).scan(any(), any(), any());
    }

    @Test
    void scannerErrorIsInconclusive(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project></project>");
        when(gateway.resolveBaseSha(any())).thenReturn("abc123");
        when(gateway.fetch(any(), any())).thenReturn(new SourceSnapshot("abc123", dir));
        when(runner.verify(any(), any(), any())).thenReturn(execution(0, "Tests run: 17, Failures: 0, Errors: 0, Skipped: 0\n"));
        when(runner.scan(any(), any(), any())).thenReturn(execution(127, ""));
        queue.setPaused(false, "test");
        var id = scans.create("test", "pilot-fixture", "scanner-" + UUID.randomUUID());
        scans.run(id);
        assertEquals("FAILED", repository.findById(id).orElseThrow().getState());
        assertEquals("SCAN_INCONCLUSIVE", repository.findById(id).orElseThrow().getReasonCode());
    }

    private static CommandRunner.Execution execution(int exit, String output) {
        return new CommandRunner.Execution(exit, output, 5, false, false, "process", List.of("tool"), false);
    }
}
