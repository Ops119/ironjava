package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public record ResolvedRepository(
        String id,
        String displayName,
        SourceKind source,
        Path localPath,
        String githubOwner,
        String githubRepo,
        String allowedBranch,
        long installationId,
        Set<String> excludedDependencies
) {
    public boolean canPublish() {
        return source == SourceKind.GITHUB
                && installationId > 0
                && githubOwner != null && !githubOwner.isBlank()
                && githubRepo != null && !githubRepo.isBlank();
    }

    public String slug() {
        return githubOwner + "/" + githubRepo;
    }
}

