package com.example.quant.strategydecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyDecisionRepository extends JpaRepository<StrategyDecisionEntity, String> {
    Optional<StrategyDecisionEntity> findByUserNameAndStrategyIdAndInstIdAndTimeframeAndCandleTimestamp(
            String userName, String strategyId, String instId, String timeframe, Instant candleTimestamp);

    Optional<StrategyDecisionEntity> findByClientOrderId(String clientOrderId);

    List<StrategyDecisionEntity> findByStatusAndUpdatedAtBefore(StrategyDecisionStatus status, Instant cutoff);
}
