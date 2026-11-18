package com.example.quant.account;

import java.util.List;
import java.util.Optional;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ClosePositionRecordRepository extends JpaRepository<ClosePositionRecordEntity, Long> {
    List<ClosePositionRecordEntity> findByUserNameOrderByCreatedAtDesc(String userName, Pageable pageable);

    long countByUserName(String userName);

    List<ClosePositionRecordEntity> findByStatus(String status);

    List<ClosePositionRecordEntity> findByUserNameAndStatus(String userName, String status);

    List<ClosePositionRecordEntity> findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(String userName,
                                                                                        String pendingOrderId);

    Optional<ClosePositionRecordEntity> findByUserNameAndCloseClOrdId(String userName, String closeClOrdId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ClosePositionRecordEntity record
               set record.status = 'CLOSE_SUBMITTING',
                   record.requestSent = false,
                   record.errorMessage = null,
                   record.errorClass = null,
                   record.recoveryResult = 'RETRY_CLAIMED',
                   record.updatedAt = :updatedAt
             where record.id = :id
               and record.status in ('CLOSE_FAILED_BEFORE_SEND', 'CLOSE_REJECTED')
            """)
    int claimRetry(@Param("id") Long id, @Param("updatedAt") Instant updatedAt);
}
