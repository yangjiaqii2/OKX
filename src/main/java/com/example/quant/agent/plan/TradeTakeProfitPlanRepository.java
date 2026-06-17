package com.example.quant.agent.plan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeTakeProfitPlanRepository extends JpaRepository<TradeTakeProfitPlanEntity, Long> {
    void deleteByTradePlanId(String tradePlanId);

    List<TradeTakeProfitPlanEntity> findByTradePlanIdOrderByLevelNoAsc(String tradePlanId);
}
