package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public class PublishReconcileException extends RuntimeException {
    public PublishReconcileException(String message) {
        super(message);
    }
}

