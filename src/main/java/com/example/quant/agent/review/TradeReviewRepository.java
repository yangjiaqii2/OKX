package com.example.quant.agent.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeReviewRepository extends JpaRepository<TradeReviewEntity, Long> {
    boolean existsByClosePositionRecordId(Long closePositionRecordId);

    Optional<TradeReviewEntity> findFirstByClosePositionRecordId(Long closePositionRecordId);
}
