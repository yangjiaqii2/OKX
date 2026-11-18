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

    String CONTROL_SIGNALS = "('NEUTRAL','NO_TRADE','WAIT_OVERHEATED','WAIT_OVERSOLD')";

    String DUE_COHORT = """
            with control_cohort as (
                select s.id, row_number() over (
                    partition by s.signal_type, floor(unix_timestamp(s.created_at) / 86400)
                    order by mod(s.id * 104729, 1000003), s.id
                ) as sample_rank
                from agent_contract_candidate_score s
                where s.scan_run_id is not null and s.signal_type in
            """ + CONTROL_SIGNALS + """
            )
            select o.* from candidate_future_outcome o
            join agent_contract_candidate_score s on s.id = o.snapshot_id
            left join control_cohort c on c.id = s.id
            where o.status in (:statuses)
              and (o.next_retry_at is null or o.next_retry_at <= :now)
              and (c.id is null or mod(c.sample_rank - 1, 20) = 0)
            """;

    String FOCUS = """
            (s.allow_trade = true or s.total_score >= 65
             or s.signal_type in ('TREND_SHORT','TREND_LONG','PULLBACK_LONG','STRONG_LONG'))
            """;

    String RETRY_ORDER = """
              case when o.last_attempt_at is null then 0 else 1 end,
              o.last_attempt_at, o.maturity_at, o.id
            """;

    @Query(value = DUE_COHORT + """
              and o.maturity_at >= :cutoff and o.maturity_at <= :now
            order by case when o.horizon_minutes = 240 and c.id is null and
            """ + FOCUS + " then 0 else 1 end, " + RETRY_ORDER, nativeQuery = true)
    List<CandidateFutureOutcomeEntity> findRecentDue(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable);

    @Query(value = DUE_COHORT + """
              and o.maturity_at < :cutoff
              and c.id is null
              and
            """ + FOCUS + """
            order by case when o.horizon_minutes = 240 then 0 else 1 end,
            """ + RETRY_ORDER, nativeQuery = true)
    List<CandidateFutureOutcomeEntity> findHistoricalDue(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("now") Instant now,
            Pageable pageable);

    @Query(value = DUE_COHORT + """
              and o.maturity_at < :cutoff and c.id is not null
            order by
            """ + RETRY_ORDER, nativeQuery = true)
    List<CandidateFutureOutcomeEntity> findHistoricalControlDue(
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
