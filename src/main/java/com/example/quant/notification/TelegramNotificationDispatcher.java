package com.example.quant.notification;

import com.example.quant.agent.event.TradeEventRepository;
import com.example.quant.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TelegramNotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationDispatcher.class);
    private final NotificationProperties properties;
    private final NotificationOutboxService outbox;
    private final TradeEventRepository events;
    private final TelegramMessageRenderer renderer;
    private final TelegramSender sender;
    private final TradingProperties trading;

    public TelegramNotificationDispatcher(NotificationProperties properties, NotificationOutboxService outbox,
                                          TradeEventRepository events, TelegramMessageRenderer renderer,
                                          TelegramSender sender, TradingProperties trading) {
        this.properties = properties; this.outbox = outbox; this.events = events;
        this.renderer = renderer; this.sender = sender; this.trading = trading;
    }

    @Scheduled(fixedDelayString = "${notification.dispatch-interval-ms:10000}")
    public void dispatch() {
        if (!properties.enabled() || !properties.telegram().configured()) return;
        for (NotificationOutboxEntity item : outbox.claimDue(20)) {
            TelegramSendResult result = events.findById(item.getTradeEventId())
                    .map(event -> sender.send(renderer.render(event, trading.mode())))
                    .orElseGet(() -> TelegramSendResult.failed(0, false, java.time.Duration.ZERO, "TRADE_EVENT_NOT_FOUND"));
            outbox.complete(item, result);
            if (!result.sent()) log.warn("Telegram notification failed eventId={} retryable={} http={}",
                    item.getTradeEventId(), result.retryable(), result.httpStatus());
        }
    }
}
