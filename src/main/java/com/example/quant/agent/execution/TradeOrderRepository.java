package com.example.quant.agent.execution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByReduceOnlyFalseAndStatusIn(Collection<String> statuses);

    Optional<TradeOrderEntity> findFirstByOkxOrdId(String okxOrdId);

    Optional<TradeOrderEntity> findFirstByUserNameAndOkxOrdId(String userName, String okxOrdId);

    Optional<TradeOrderEntity> findFirstByClOrdId(String clOrdId);

    Optional<TradeOrderEntity> findFirstByUserNameAndClOrdId(String userName, String clOrdId);

    List<TradeOrderEntity> findByStatus(String status);

    long countByUserNameAndStatusIn(String userName, Collection<String> statuses);

    List<TradeOrderEntity> findByPendingOrderIdOrderByCreatedAtAsc(String pendingOrderId);

    List<TradeOrderEntity> findByUserNameAndPendingOrderIdOrderByCreatedAtAsc(String userName, String pendingOrderId);

    List<TradeOrderEntity> findByPendingOrderIdAndReduceOnlyTrueAndStatusIn(String pendingOrderId,
                                                                            Collection<String> statuses);

    List<TradeOrderEntity> findByInstIdAndReduceOnlyTrueAndStatusIn(String instId,
                                                                    Collection<String> statuses);

}
