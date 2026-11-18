package com.example.quant.strategydecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StrategyDecisionRepository extends JpaRepository<StrategyDecisionEntity, String> {
    Optional<StrategyDecisionEntity> findByUserNameAndStrategyIdAndInstIdAndTimeframeAndCandleTimestamp(
            String userName, String strategyId, String instId, String timeframe, Instant candleTimestamp);

    Optional<StrategyDecisionEntity> findByClientOrderId(String clientOrderId);

    List<StrategyDecisionEntity> findByStatusAndUpdatedAtBefore(StrategyDecisionStatus status, Instant cutoff);

    @Query("""
            select count(d) from StrategyDecisionEntity d where d.userName = :owner and d.status = :status
              and d.signalExclusionReason is null
              and (d.reason is null or d.reason not in ('auto_trade_disabled_by_config', 'auto_trade_runtime_switch_off'))
            """)
    long countObservedSignals(@Param("owner") String owner, @Param("status") StrategyDecisionStatus status);
}
