package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public class StaleBaseException extends RuntimeException {
    public StaleBaseException() {
        super("STALE_BASE");
    }
}

