package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public record PublishRequest(String baseSha, String branch, String pomPath, String pomContents, String title, String body, String patchHash) {
}

