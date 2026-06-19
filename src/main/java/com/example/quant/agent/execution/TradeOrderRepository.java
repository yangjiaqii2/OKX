package com.example.quant.agent.execution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByReduceOnlyFalseAndStatusIn(Collection<String> statuses);

    Optional<TradeOrderEntity> findFirstByOkxOrdId(String okxOrdId);

    Optional<TradeOrderEntity> findFirstByClOrdId(String clOrdId);

    List<TradeOrderEntity> findByStatus(String status);

    List<TradeOrderEntity> findByPendingOrderIdOrderByCreatedAtAsc(String pendingOrderId);

    List<TradeOrderEntity> findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(String pendingOrderId,
                                                                            Collection<String> statuses);
}
