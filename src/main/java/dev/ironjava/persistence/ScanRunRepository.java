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

public interface ScanRunRepository extends JpaRepository<ScanRunEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScanRunEntity s where s.id = :id")
    Optional<ScanRunEntity> lockById(@Param("id") UUID id);

    @Query("""
            select s.id from ScanRunEntity s
            where s.state = 'QUEUED' and (s.leaseUntil is null or s.leaseUntil < :now)
            order by s.createdAt asc
            """)
    List<UUID> claimable(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select s from ScanRunEntity s
            where s.leaseOwner is not null and s.leaseUntil < :now
              and s.state in ('QUEUED','BASELINING','SCANNING')
            """)
    List<ScanRunEntity> expired(@Param("now") Instant now);

    List<ScanRunEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select s from ScanRunEntity s
            where s.leaseOwner = :owner
              and s.state in ('QUEUED','BASELINING','SCANNING')
            """)
    List<ScanRunEntity> leasedBy(@Param("owner") String owner);

    Optional<ScanRunEntity> findFirstByRepositoryIdAndStateOrderByCreatedAtDesc(String repositoryId, String state);
}

