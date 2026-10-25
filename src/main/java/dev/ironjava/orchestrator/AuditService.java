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
public class AuditService {
    private final AuditEventRepository repository;

    AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void record(String actor, String event, UUID runId, String outcome) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setActor(actor == null ? "system" : actor);
        entity.setEventType(event);
        entity.setRunId(runId);
        entity.setOutcome(LogRedactor.truncate(outcome, 2000));
        entity.setCreatedAt(Instant.now());
        repository.save(entity);
    }

    public List<AuditEventEntity> forRun(UUID runId) {
        return repository.findByRunIdOrderByCreatedAtAsc(runId);
    }
}
