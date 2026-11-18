package com.example.quant.notification;

import com.example.quant.agent.event.TradeEventEntity;
import com.example.quant.agent.event.TradeEventType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationOutboxService {
    private static final Set<TradeEventType> IMMEDIATE = EnumSet.of(
            TradeEventType.PROTECTION_SUBMITTED,
            TradeEventType.ENTRY_SUBMIT_UNKNOWN, TradeEventType.ENTRY_TIMEOUT_CANCELLED,
            TradeEventType.PROTECTION_FAILED, TradeEventType.SIDEWAYS_TP_REPLACED,
            TradeEventType.MAX_HOLD_CLOSE_SUBMITTED, TradeEventType.MANUAL_CLOSE_SUBMITTED,
            TradeEventType.CLOSE_CONFIRMED, TradeEventType.RECOVERY_FAILED);
    private final NotificationOutboxRepository repository;
    private final NotificationProperties properties;

    public NotificationOutboxService(NotificationOutboxRepository repository, NotificationProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public void enqueue(TradeEventEntity event) {
        if (!properties.enabled() || !properties.telegram().enabled() || event == null || event.getId() == null) return;
        TradeEventType type;
        try { type = TradeEventType.valueOf(event.getEventType()); }
        catch (RuntimeException ex) { return; }
        if (!IMMEDIATE.contains(type) || repository.existsByTradeEventIdAndChannel(event.getId(), "TELEGRAM")) return;
        Instant now = Instant.now();
        NotificationOutboxEntity item = new NotificationOutboxEntity();
        item.setTradeEventId(event.getId());
        item.setChannel("TELEGRAM");
        item.setStatus("PENDING");
        item.setNextAttemptAt(now);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        try { repository.save(item); } catch (DataIntegrityViolationException ignored) { /* database unique key is final dedupe */ }
    }

    @Transactional
    public List<NotificationOutboxEntity> claimDue(int limit) {
        Instant now = Instant.now();
        List<NotificationOutboxEntity> due = new ArrayList<>(repository
                .findByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAt(
                        List.of("PENDING", "RETRY"), now, PageRequest.of(0, Math.max(1, Math.min(20, limit)))));
        if (due.size() < limit) {
            due.addAll(repository.findByStatusAndLeaseUntilLessThanEqualOrderByCreatedAt(
                    "SENDING", now, PageRequest.of(0, limit - due.size())));
        }
        due.forEach(item -> {
            item.setStatus("SENDING");
            item.setLeaseUntil(now.plusSeconds(60));
            item.setUpdatedAt(now);
        });
        return List.copyOf(repository.saveAll(due));
    }

    @Transactional
    public void complete(NotificationOutboxEntity item, TelegramSendResult result) {
        NotificationOutboxEntity current = repository.findById(item.getId()).orElse(null);
        if (current == null || "SENT".equals(current.getStatus())) return;
        int attempts = current.getAttempts() + 1;
        current.setAttempts(attempts);
        current.setLeaseUntil(null);
        current.setLastHttpStatus(result.httpStatus() == 0 ? null : result.httpStatus());
        current.setLastError(result.error());
        current.setUpdatedAt(Instant.now());
        if (result.sent()) {
            current.setStatus("SENT");
        } else if (result.retryable() && attempts < properties.telegram().maxAttempts()) {
            current.setStatus("RETRY");
            long delay = result.retryAfter().isZero() ? Math.min(300, (1L << Math.min(attempts, 6)) * 5) : result.retryAfter().toSeconds();
            current.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(Math.max(1, delay))));
        } else {
            current.setStatus("FAILED");
        }
        repository.save(current);
    }

    public NotificationStatus status() {
        return new NotificationStatus(properties.enabled() && properties.telegram().configured(),
                repository.countByStatus("PENDING"), repository.countByStatus("RETRY"), repository.countByStatus("FAILED"),
                repository.findTopByStatusOrderByUpdatedAtDesc("SENT").map(NotificationOutboxEntity::getUpdatedAt).orElse(null));
    }

    public record NotificationStatus(boolean enabled, long pending, long retry, long failed, Instant lastSentAt) {}
}
