package com.example.quant.agent.evaluation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

public interface CandidateFutureOutcomeRepository extends JpaRepository<CandidateFutureOutcomeEntity, Long> {
    List<CandidateFutureOutcomeEntity> findByStatusInAndMaturityAtLessThanEqual(
            Collection<String> statuses, Instant maturityAt);

    @Query("""
            select o from CandidateFutureOutcomeEntity o
            where o.status in :statuses
              and o.maturityAt >= :cutoff and o.maturityAt <= :now
              and (o.nextRetryAt is null or o.nextRetryAt <= :now)
            order by case when o.lastAttemptAt is null then 0 else 1 end,
                     o.lastAttemptAt asc, o.maturityAt asc, o.id asc
            """)
    List<CandidateFutureOutcomeEntity> findRecentDue(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable);

    @Query("""
            select o from CandidateFutureOutcomeEntity o
            where o.status in :statuses
              and o.maturityAt < :cutoff
              and (o.nextRetryAt is null or o.nextRetryAt <= :now)
            order by case when o.lastAttemptAt is null then 0 else 1 end,
                     o.lastAttemptAt asc, o.maturityAt asc, o.id asc
            """)
    List<CandidateFutureOutcomeEntity> findHistoricalDue(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable);

    Optional<CandidateFutureOutcomeEntity> findBySnapshotIdAndHorizonMinutes(Long snapshotId, Integer horizonMinutes);

    List<CandidateFutureOutcomeEntity> findBySnapshotIdIn(Collection<Long> snapshotIds);

    long countByStatus(String status);

    long countByStatusInAndMaturityAtLessThanEqual(Collection<String> statuses, Instant maturityAt);

    Optional<CandidateFutureOutcomeEntity> findFirstByStatusInOrderByMaturityAtAsc(Collection<String> statuses);
}
