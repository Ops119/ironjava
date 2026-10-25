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

public interface RemediationRepository extends JpaRepository<RemediationRunEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RemediationRunEntity r where r.id = :id")
    Optional<RemediationRunEntity> lockById(@Param("id") UUID id);

    @Query("""
            select r.id from RemediationRunEntity r
            where r.state = 'QUEUED' and (r.leaseUntil is null or r.leaseUntil < :now)
            order by r.createdAt asc
            """)
    List<UUID> claimablePlanning(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select r.id from RemediationRunEntity r
            where r.state = 'VERIFYING' and (r.leaseUntil is null or r.leaseUntil < :now)
            order by r.createdAt asc
            """)
    List<UUID> claimableVerification(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select r.id from RemediationRunEntity r
            where r.state = 'PUBLISHING' and r.publishAttempted = false
              and (r.leaseUntil is null or r.leaseUntil < :now)
            order by r.createdAt asc
            """)
    List<UUID> claimablePublish(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select r from RemediationRunEntity r
            where r.leaseOwner is not null and r.leaseUntil < :now
              and r.state in ('QUEUED','PLANNING','PATCHING','VERIFYING','PUBLISHING')
            """)
    List<RemediationRunEntity> expired(@Param("now") Instant now);

    Optional<RemediationRunEntity> findFirstByDedupKeyAndStateNotIn(String dedupKey, List<String> states);

    List<RemediationRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select r from RemediationRunEntity r
            where r.leaseOwner = :owner
              and r.state in ('QUEUED','PLANNING','PATCHING','VERIFYING','PUBLISHING')
            """)
    List<RemediationRunEntity> leasedBy(@Param("owner") String owner);
}

