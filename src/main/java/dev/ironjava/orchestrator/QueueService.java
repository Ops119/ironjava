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
public class QueueService {
    private final QueueControlRepository repository;
    private final AuditService audit;

    QueueService(QueueControlRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    public boolean paused() {
        return repository.findById((short) 1).map(QueueControlEntity::isPaused).orElse(false);
    }

    public void setPaused(boolean paused, String actor) {
        QueueControlEntity entity = repository.findById((short) 1).orElseThrow();
        entity.setPaused(paused);
        entity.setUpdatedAt(Instant.now());
        entity.setUpdatedBy(actor);
        repository.save(entity);
        audit.record(actor, paused ? "QUEUE_PAUSED" : "QUEUE_RESUMED", null, paused ? "paused" : "resumed");
    }
}
