package com.example.quant.agent.execution;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByReduceOnlyFalseAndStatusIn(Collection<String> statuses);

    List<TradeOrderEntity> findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(String pendingOrderId,
                                                                            Collection<String> statuses);
}
