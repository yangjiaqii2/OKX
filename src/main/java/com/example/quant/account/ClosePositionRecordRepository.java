package com.example.quant.account;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClosePositionRecordRepository extends JpaRepository<ClosePositionRecordEntity, Long> {
    List<ClosePositionRecordEntity> findByUserNameOrderByCreatedAtDesc(String userName, Pageable pageable);

    List<ClosePositionRecordEntity> findByStatus(String status);
}
