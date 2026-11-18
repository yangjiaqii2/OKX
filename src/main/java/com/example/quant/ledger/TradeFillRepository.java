package com.example.quant.ledger;

import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeFillRepository extends JpaRepository<TradeFillEntity, Long> {
    Optional<TradeFillEntity> findByUserNameAndInstIdAndTradeId(String userName, String instId, String tradeId);
    List<TradeFillEntity> findByUserName(String userName);
    List<TradeFillEntity> findByUserNameAndPendingOrderId(String userName, String pendingOrderId);
    List<TradeFillEntity> findByPendingOrderId(String pendingOrderId);
}
