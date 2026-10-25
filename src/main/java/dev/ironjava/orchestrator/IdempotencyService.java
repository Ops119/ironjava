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
public class IdempotencyService {
    private final IdempotencyRepository repository;

    IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public sealed interface Begin permits Proceed, Replay, Reject {
    }

    public record Proceed() implements Begin {
    }

    public record Replay(int status, String body) implements Begin {
    }

    public record Reject(int status, String code) implements Begin {
    }

    public Begin begin(String actor, String operation, String key, String hash) {
        if (key == null || key.isBlank() || key.length() > 200) {
            return new Reject(422, "IDEMPOTENCY_KEY_REQUIRED");
        }
        Optional<IdempotencyEntity> existing = repository.findByActorAndOperationAndIdempotencyKey(actor, operation, key);
        if (existing.isEmpty()) {
            return new Proceed();
        }
        if (!existing.get().getRequestHash().equals(hash)) {
            return new Reject(409, "IDEMPOTENCY_KEY_REUSED");
        }
        return new Replay(existing.get().getResponseStatus(), existing.get().getResponseBody());
    }

    public void store(String actor, String operation, String key, String hash, int status, String body, UUID resourceId) {
        IdempotencyEntity entity = new IdempotencyEntity();
        entity.setId(UUID.randomUUID());
        entity.setActor(actor);
        entity.setOperation(operation);
        entity.setIdempotencyKey(key);
        entity.setRequestHash(hash);
        entity.setResponseStatus(status);
        entity.setResponseBody(body);
        entity.setResourceId(resourceId);
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
    }
}
