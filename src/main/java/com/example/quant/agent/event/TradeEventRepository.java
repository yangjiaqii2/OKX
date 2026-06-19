package com.example.quant.agent.event;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeEventRepository extends JpaRepository<TradeEventEntity, Long> {
    List<TradeEventEntity> findByUserNameOrderByCreatedAtDesc(String userName, Pageable pageable);

    List<TradeEventEntity> findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
            String userName,
            String pendingOrderId,
            Pageable pageable
    );
}
