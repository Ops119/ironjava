package dev.ironjava.orchestrator;

import dev.ironjava.audit.LogRedactor;
import dev.ironjava.config.IronJavaProperties;
import dev.ironjava.domain.Domain.SourceKind;
import dev.ironjava.gateway.ResolvedRepository;
import dev.ironjava.gateway.Tree;
import dev.ironjava.persistence.AuditEventEntity;
import dev.ironjava.persistence.AuditEventRepository;
import dev.ironjava.persistence.IdempotencyEntity;
import dev.ironjava.persistence.IdempotencyRepository;
import dev.ironjava.persistence.QueueControlEntity;
import dev.ironjava.persistence.QueueControlRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class WorkspaceManager {
    private final IronJavaProperties properties;

    WorkspaceManager(IronJavaProperties properties) {
        this.properties = properties;
    }

    public Path create(UUID runId) throws IOException {
        Path root = root();
        Files.createDirectories(root);
        Path dir = root.resolve(runId.toString()).normalize();
        if (!dir.startsWith(root)) {
            throw new IOException("workspace escape");
        }
        Tree.delete(dir);
        Files.createDirectories(dir);
        return dir;
    }

    public void delete(UUID runId) {
        Tree.delete(root().resolve(runId.toString()).normalize());
    }

    public void purgeExpired() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.getLimits().getWorkspaceRetentionHours(), ChronoUnit.HOURS);
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.getLastModifiedTime(child).toInstant().isBefore(cutoff)) {
                    Tree.delete(child);
                }
            }
        } catch (IOException ignored) {
            // The next sweep retries.
        }
    }

    private Path root() {
        return Path.of(properties.getRunner().getWorkspaceRoot()).toAbsolutePath().normalize();
    }
}

