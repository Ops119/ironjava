package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public interface MavenMetadataClient {
    Result list(String groupId, String artifactId);

    record Result(boolean reachable, java.util.List<String> versions) {
    }
}

