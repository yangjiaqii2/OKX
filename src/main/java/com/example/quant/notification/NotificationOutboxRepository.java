package com.example.quant.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, Long> {
    boolean existsByTradeEventIdAndChannel(Long tradeEventId, String channel);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<NotificationOutboxEntity> findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
            List<String> statuses, Instant now, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<NotificationOutboxEntity> findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAt(
            String status, Instant now, Pageable pageable);
    long countByStatus(String status);
    Optional<NotificationOutboxEntity> findTopByStatusOrderByUpdatedAtDesc(String status);
}
