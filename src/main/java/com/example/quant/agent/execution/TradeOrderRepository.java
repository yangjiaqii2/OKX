package com.example.quant.agent.execution;

import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeOrderRepository extends JpaRepository<TradeOrderEntity, Long> {
    boolean existsByReduceOnlyFalseAndStatusIn(Collection<String> statuses);
}
