package com.example.quant.controller;

import com.example.quant.notification.NotificationOutboxService;
import com.example.quant.notification.TelegramSendResult;
import com.example.quant.notification.TelegramSender;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quant/notifications")
public class NotificationController {
    private final NotificationOutboxService outbox;
    private final TelegramSender sender;
    public NotificationController(NotificationOutboxService outbox, TelegramSender sender) {
        this.outbox = outbox; this.sender = sender;
    }
    @GetMapping("/status") public Object status() { return outbox.status(); }
    @PostMapping("/telegram/test") public Object test() {
        TelegramSendResult result = sender.send("[TEST] OKX 量化交易通知连接测试");
        return Map.of("sent", result.sent(), "retryable", result.retryable(), "httpStatus", result.httpStatus(),
                "message", result.sent() ? "Telegram test notification sent" : String.valueOf(result.error()));
    }
}
