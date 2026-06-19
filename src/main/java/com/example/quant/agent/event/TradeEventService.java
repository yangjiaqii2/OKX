package com.example.quant.agent.event;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.auth.AuthUserContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeEventService {
    private final TradeEventRepository repository;
    private final Clock clock;

    @Autowired
    public TradeEventService(TradeEventRepository repository) {
        this(repository, Clock.systemUTC());
    }

    public TradeEventService(TradeEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Transactional
    public TradeEventView record(TradeEventPayload payload) {
        if (payload == null || payload.eventType() == null) {
            throw new IllegalArgumentException("交易事件类型不能为空");
        }
        TradeEventEntity entity = new TradeEventEntity();
        entity.setUserName(userName(payload.userName()));
        entity.setInstId(trimToNull(payload.instId()));
        entity.setPendingOrderId(trimToNull(payload.pendingOrderId()));
        entity.setAutoTradeRecordId(payload.autoTradeRecordId());
        entity.setTradeOrderId(payload.tradeOrderId());
        entity.setEventType(payload.eventType().name());
        entity.setOldStatus(trimToNull(payload.oldStatus()));
        entity.setNewStatus(trimToNull(payload.newStatus()));
        entity.setReasonCode(trimToNull(payload.reasonCode()));
        entity.setReasonMessage(trimToNull(payload.reasonMessage()));
        entity.setOkxOrdId(trimToNull(payload.okxOrdId()));
        entity.setClOrdId(trimToNull(payload.clOrdId()));
        entity.setAlgoId(trimToNull(payload.algoId()));
        entity.setCreatedAt(Instant.now(clock));
        return TradeEventView.from(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<TradeEventView> recentForPendingOrder(String pendingOrderId, int limit) {
        if (!hasText(pendingOrderId)) {
            return List.of();
        }
        return repository.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc(
                        currentUsername(),
                        pendingOrderId.trim(),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 100)))
                )
                .stream()
                .map(TradeEventView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TradeEventView> recent(int limit) {
        return repository.findByUserNameOrderByCreatedAtDesc(
                        currentUsername(),
                        PageRequest.of(0, Math.max(1, Math.min(limit, 100)))
                )
                .stream()
                .map(TradeEventView::from)
                .toList();
    }

    private static String userName(String explicit) {
        if (hasText(explicit)) {
            return explicit.trim();
        }
        return currentUsername();
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
