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
public class RepositoryCatalog {
    private final IronJavaProperties properties;

    RepositoryCatalog(IronJavaProperties properties) {
        this.properties = properties;
    }

    public List<ResolvedRepository> all() {
        return properties.getRepositories().stream().map(this::map).toList();
    }

    public Optional<ResolvedRepository> find(String id) {
        return all().stream().filter(repository -> repository.id().equals(id)).findFirst();
    }

    public ResolvedRepository require(String id) {
        return find(id).orElseThrow(() -> new RequestExceptions.Unsupported("UNKNOWN_REPOSITORY"));
    }

    private ResolvedRepository map(IronJavaProperties.RepositorySpec spec) {
        if (spec.getId() == null || spec.getId().isBlank()) {
            throw new IllegalStateException("repository id is required");
        }
        if (!"verify".equals(spec.getBuildProfile())) {
            throw new IllegalStateException("unsupported build profile " + spec.getBuildProfile());
        }
        SourceKind source = "github".equalsIgnoreCase(spec.getSource()) ? SourceKind.GITHUB : SourceKind.LOCAL;
        Set<String> excluded = spec.getExcludedDependencies() == null
                ? Set.of()
                : spec.getExcludedDependencies().stream().collect(Collectors.toSet());
        Path local = spec.getLocalPath() == null || spec.getLocalPath().isBlank() ? null : Path.of(spec.getLocalPath());
        return new ResolvedRepository(spec.getId(), spec.getDisplayName(), source, local, spec.getGithubOwner(),
                spec.getGithubRepo(), spec.getAllowedBranch(), spec.getInstallationId(), excluded);
    }
}
