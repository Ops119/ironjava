package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public record PublishResult(String url, int number, String branch, boolean adoptedExisting) {
}

