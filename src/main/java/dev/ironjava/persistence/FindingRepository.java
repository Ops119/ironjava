package dev.ironjava.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FindingRepository extends JpaRepository<FindingEntity, UUID> {
    List<FindingEntity> findByScanRunIdOrderByCoordinateAsc(UUID scanRunId);

    List<FindingEntity> findByRepositoryIdOrderByCreatedAtDesc(String repositoryId);
}

