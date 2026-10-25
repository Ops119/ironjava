package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public interface RepositoryGateway {
    String resolveBaseSha(ResolvedRepository repository);

    SourceSnapshot fetch(ResolvedRepository repository, String sha);

    PublishResult publish(ResolvedRepository repository, PublishRequest request);

    /**
     * True only when a GitHub write can be started. A false result blocks before {@code publish_attempted}
     * is set, so a missing App is not recorded as a partial publish.
     */
    boolean publishConfigured(ResolvedRepository repository);
}
