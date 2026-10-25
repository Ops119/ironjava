package dev.ironjava.gateway;

import dev.ironjava.domain.Domain.SourceKind;

import java.nio.file.Path;
import java.util.Set;

public class PublicationNotConfiguredException extends RuntimeException {
    public PublicationNotConfiguredException() {
        super("PUBLICATION_NOT_CONFIGURED");
    }
}

