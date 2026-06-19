package com.example.quant.agent.execution;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AutoTradeRecordRepository extends JpaRepository<AutoTradeRecordEntity, Long>,
        JpaSpecificationExecutor<AutoTradeRecordEntity> {
    Optional<AutoTradeRecordEntity> findFirstByPendingOrderIdOrderByCreatedAtDesc(String pendingOrderId);
}
