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

public final class RequestExceptions {
    private RequestExceptions() {
    }

    public static final class Unsupported extends RuntimeException {
        private final String code;

        public Unsupported(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static final class Conflict extends RuntimeException {
        private final String code;

        public Conflict(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
