package com.example.quant.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.quant.agent.event.TradeEventEntity;
import com.example.quant.agent.event.TradeEventType;
import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.ClosePositionRecordEntity;
import com.example.quant.account.ClosePositionRecordRepository;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.agent.execution.TradeOrderEntity;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.config.TradingMode;
import com.example.quant.ledger.TradeFillEntity;
import com.example.quant.ledger.TradeFillRepository;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.okxtrade.OkxInstrumentRulesProvider;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderEntity;
import com.example.quant.order.PendingOrderRepository;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelegramNotificationTest {
    private static final String PENDING_ID = "00000000-0000-0000-0000-000000000001";
    @Test
    void enqueuesOnlyActionableEventsAndDeduplicates() {
        NotificationOutboxRepository repository = mock(NotificationOutboxRepository.class);
        NotificationProperties properties = enabledProperties();
        NotificationOutboxService service = new NotificationOutboxService(repository, properties);
        TradeEventEntity event = mock(TradeEventEntity.class);
        when(event.getId()).thenReturn(7L);
        when(event.getEventType()).thenReturn(TradeEventType.PROTECTION_SUBMITTED.name());

        service.enqueue(event);
        when(repository.existsByTradeEventIdAndChannel(7L, "TELEGRAM")).thenReturn(true);
        service.enqueue(event);

        verify(repository, times(1)).save(any(NotificationOutboxEntity.class));

        reset(repository);
        when(event.getEventType()).thenReturn(TradeEventType.ENTRY_SUBMITTED.name());
        service.enqueue(event);
        when(event.getEventType()).thenReturn(TradeEventType.ENTRY_FILLED.name());
        service.enqueue(event);
        verify(repository, never()).save(any(NotificationOutboxEntity.class));
    }

    @Test
    void sendsJsonAndClassifiesTelegramResponses() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bottoken/sendMessage", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBody.set(request);
            byte[] response = (request.contains("chat_id") ? "{\"ok\":true}" : "{\"ok\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TelegramSender sender = new TelegramSender(enabledProperties(), new ObjectMapper(), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertThat(sender.send("test").sent()).isTrue();
            assertThat(requestBody.get()).contains("\"parse_mode\":\"HTML\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesRateLimitAuthServerAndMalformedResponses() throws Exception {
        int[] statuses = {429, 401, 500, 400};
        String[] bodies = {
                "{\"ok\":false,\"description\":\"rate limited\",\"parameters\":{\"retry_after\":7}}",
                "{\"ok\":false,\"description\":\"unauthorized\"}",
                "{\"ok\":false,\"description\":\"server error\"}",
                "not-json"
        };
        AtomicInteger next = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bottoken/sendMessage", exchange -> {
            int index = next.getAndIncrement();
            byte[] response = bodies[index].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statuses[index], response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            TelegramSender sender = new TelegramSender(enabledProperties(), new ObjectMapper(), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            TelegramSendResult limited = sender.send("test");
            TelegramSendResult unauthorized = sender.send("test");
            TelegramSendResult serverError = sender.send("test");
            TelegramSendResult malformed = sender.send("test");

            assertThat(limited.retryable()).isTrue();
            assertThat(limited.httpStatus()).isEqualTo(429);
            assertThat(limited.retryAfter()).hasSeconds(7);
            assertThat(unauthorized.retryable()).isFalse();
            assertThat(unauthorized.httpStatus()).isEqualTo(401);
            assertThat(serverError.retryable()).isTrue();
            assertThat(serverError.httpStatus()).isEqualTo(500);
            assertThat(malformed.retryable()).isFalse();
            assertThat(malformed.httpStatus()).isEqualTo(400);
            assertThat(malformed.error()).isEqualTo("MALFORMED_RESPONSE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesTimeoutAsRetryableWithoutHttpStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/bottoken/sendMessage", exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        try {
            NotificationProperties properties = enabledProperties();
            properties.telegram().setSendTimeoutSeconds(1);
            TelegramSender sender = new TelegramSender(properties, new ObjectMapper(), HttpClient.newHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());

            TelegramSendResult result = sender.send("test");

            assertThat(result.sent()).isFalse();
            assertThat(result.retryable()).isTrue();
            assertThat(result.httpStatus()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rendersActionableAlertAsHtmlWithoutInternalIds() {
        TradeEventEntity event = new TradeEventEntity();
        event.setEventType(TradeEventType.ENTRY_SUBMIT_UNKNOWN.name());
        event.setInstId("BTC<&-USDT-SWAP");
        event.setOkxOrdId("secret-order-id");
        event.setClOrdId("secret-client-id");
        event.setCreatedAt(Instant.parse("2026-08-11T00:00:00Z"));
        String message = rendererFixture().renderer.render(event, TradingMode.DEMO);
        assertThat(message)
                .contains("<b>DEMO · 订单状态未知</b>", "BTC&lt;&amp;-USDT-SWAP", "请勿重复下单")
                .doesNotContain("ordId", "clOrdId", "secret-order-id", "secret-client-id", "0.00");
    }

    @Test
    void rendersFilledEntryUsingExchangeFactsAndContractConversion() {
        RendererFixture fixture = rendererFixture();
        when(fixture.pendingOrders.findById(PENDING_ID)).thenReturn(Optional.of(pendingEntity()));
        when(fixture.tradeOrders.findByPendingOrderIdOrderByCreatedAtAsc(PENDING_ID))
                .thenReturn(List.of(protection("STOP_LOSS", "1872.08", "2"),
                        protection("TP1", "1928.22", "2")));
        when(fixture.fills.findByUserName("local-admin")).thenReturn(List.of(
                fill(PENDING_ID, "buy", "1890.41", "2", "2026-08-12T05:39:30Z")));
        when(fixture.rules.rules("ETH-USDT-SWAP")).thenReturn(new OkxInstrumentRules(
                "ETH-USDT-SWAP", new BigDecimal("0.01"), "ETH",
                new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.01")));
        when(fixture.positions.positions()).thenReturn(List.of(new PositionSummary(
                "ETH-USDT-SWAP", "long", "2", new BigDecimal("1890.41"), BigDecimal.ZERO,
                new BigDecimal("12.60"), new BigDecimal("12.60"), "isolated", new BigDecimal("3"),
                BigDecimal.ZERO, new BigDecimal("37.8082"), BigDecimal.ZERO, "OKX_DEMO", "MANAGED", "")));
        when(fixture.accounts.summary()).thenReturn(new AccountSummary(
                new BigDecimal("1002.18"), new BigDecimal("986.42"), "OKX_DEMO", "ok"));

        String message = fixture.renderer.render(event(TradeEventType.PROTECTION_SUBMITTED), TradingMode.DEMO);

        assertThat(message)
                .contains("<b>DEMO · 开仓完成</b>", "做多 LONG", "0.02 ETH（2 contracts）",
                        "1,890.41 USDT", "3×", "37.8082 USDT", "12.6 USDT",
                        "1,872.08 USDT", "1,928.22 USDT", "986.42 USDT", "1,002.18 USDT")
                .doesNotContain("ordId", "clOrdId", PENDING_ID);

        when(fixture.accounts.summary()).thenReturn(new AccountSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, "OKX_ERROR", "temporarily unavailable"));
        String accountUnavailable = fixture.renderer.render(event(TradeEventType.PROTECTION_SUBMITTED), TradingMode.DEMO);
        assertThat(accountUnavailable).contains("可用金额", "总资产", "同步中")
                .doesNotContain("可用金额      0 USDT", "总资产        0 USDT");
    }

    @Test
    void rendersCloseSettlementWithoutPretendingUnknownFundingIsZero() {
        RendererFixture fixture = rendererFixture();
        when(fixture.pendingOrders.findById(PENDING_ID)).thenReturn(Optional.of(pendingEntity()));
        when(fixture.tradeOrders.findByPendingOrderIdOrderByCreatedAtAsc(PENDING_ID)).thenReturn(List.of());
        when(fixture.fills.findByUserName("local-admin")).thenReturn(List.of(
                fill(PENDING_ID, "buy", "1890.41", "2", "2026-08-12T05:39:30Z"),
                fill(PENDING_ID, "sell", "1881.20", "2", "2026-08-12T07:57:30Z")));
        ClosePositionRecordEntity close = new ClosePositionRecordEntity();
        close.setUserName("local-admin"); close.setInstId("ETH-USDT-SWAP"); close.setPosSide("long");
        close.setPendingOrderId(PENDING_ID); close.setSize(new BigDecimal("2"));
        close.setAvgPx(new BigDecimal("1881.20")); close.setRealizedPnl(new BigDecimal("-0.18"));
        close.setFee(new BigDecimal("-0.03")); close.setStatus("CLOSED"); close.setSource("MANUAL");
        close.setCreatedAt(Instant.parse("2026-08-12T07:57:20Z"));
        close.setUpdatedAt(Instant.parse("2026-08-12T07:57:30Z"));
        when(fixture.closes.findByUserNameAndPendingOrderIdOrderByCreatedAtDesc("local-admin", PENDING_ID))
                .thenReturn(List.of(close));
        when(fixture.rules.rules("ETH-USDT-SWAP")).thenReturn(new OkxInstrumentRules(
                "ETH-USDT-SWAP", new BigDecimal("0.01"), "ETH",
                new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("0.01")));
        when(fixture.accounts.summary()).thenReturn(new AccountSummary(
                new BigDecimal("1001.96"), new BigDecimal("986.20"), "OKX_DEMO", "ok"));

        String message = fixture.renderer.render(event(TradeEventType.CLOSE_CONFIRMED), TradingMode.DEMO);

        assertThat(message)
                .contains("<b>DEMO · 平仓结算</b>", "🔴 <b>已确认毛亏损 -0.18 USDT</b>",
                        "开仓均价", "1,890.41 USDT", "平仓均价", "1,881.2 USDT",
                        "0.02 ETH（2 contracts）", "2小时18分钟",
                        "毛盈亏", "-0.18 USDT", "交易手续费", "-0.03 USDT",
                        "资金费", "净盈亏", "同步中",
                        "986.2 USDT", "1,001.96 USDT")
                .doesNotContain("ordId", "clOrdId", PENDING_ID, "净盈亏        -0.21");

        close.setRealizedPnl(new BigDecimal("0.50"));
        close.setFee(new BigDecimal("-0.03"));
        close.setFundingFee(BigDecimal.ZERO);
        String profit = fixture.renderer.render(event(TradeEventType.CLOSE_CONFIRMED), TradingMode.DEMO);
        assertThat(profit).contains("🟢 <b>净盈利 +0.47 USDT</b>", "净盈亏", "+0.47 USDT");

        TradeOrderEntity triggeredStop = protection("STOP_LOSS", "1872.08", "2");
        triggeredStop.setStatus("ALGO_EFFECTIVE");
        when(fixture.tradeOrders.findByPendingOrderIdOrderByCreatedAtAsc(PENDING_ID))
                .thenReturn(List.of(triggeredStop));
        assertThat(fixture.renderer.render(event(TradeEventType.CLOSE_CONFIRMED), TradingMode.DEMO))
                .contains("平仓原因", "止损触发");
    }

    @Test
    void rendersOtherLifecycleEventsAsConciseActionCards() {
        TelegramMessageRenderer renderer = rendererFixture().renderer;
        assertThat(renderer.render(event(TradeEventType.ENTRY_TIMEOUT_CANCELLED), TradingMode.DEMO))
                .contains("委托未成交，已撤销", "系统已撤销剩余委托");
        assertThat(renderer.render(event(TradeEventType.PROTECTION_FAILED), TradingMode.DEMO))
                .contains("持仓保护异常", "立即检查OKX当前仓位");
        assertThat(renderer.render(event(TradeEventType.RECOVERY_FAILED), TradingMode.DEMO))
                .contains("交易恢复异常", "自动交易可能已被阻断");
        assertThat(renderer.render(event(TradeEventType.MANUAL_CLOSE_SUBMITTED), TradingMode.DEMO))
                .contains("平仓处理中", "等待OKX成交与账本对账")
                .doesNotContain("ordId", "clOrdId", PENDING_ID);

        TradeEventEntity recovered = event(TradeEventType.PROTECTION_SUBMITTED);
        recovered.setReasonCode("MISSING_PROTECTION_RECOVERED");
        assertThat(renderer.render(recovered, TradingMode.DEMO))
                .contains("开仓数据同步中").doesNotContain("开仓完成");
    }

    private static RendererFixture rendererFixture() {
        PendingOrderRepository pendingOrders = mock(PendingOrderRepository.class);
        TradeOrderRepository tradeOrders = mock(TradeOrderRepository.class);
        ClosePositionRecordRepository closes = mock(ClosePositionRecordRepository.class);
        TradeFillRepository fills = mock(TradeFillRepository.class);
        OkxInstrumentRulesProvider rules = mock(OkxInstrumentRulesProvider.class);
        AccountSnapshotService accounts = mock(AccountSnapshotService.class);
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        return new RendererFixture(new TelegramMessageRenderer(pendingOrders, tradeOrders, closes, fills, rules,
                accounts, positions), pendingOrders, tradeOrders, closes, fills, rules, accounts, positions);
    }

    private static PendingOrderEntity pendingEntity() {
        PendingOrder order = new PendingOrder(UUID.fromString(PENDING_ID),
                MarketType.OKX_SWAP, "ETH-USDT-SWAP", TradePlanType.OPEN_LONG, "buy", "long", "market",
                new BigDecimal("1890.41"), new BigDecimal("0.02"), 3, "isolated",
                new BigDecimal("1872.08"), new BigDecimal("1928.22"), new BigDecimal("0.37"),
                new BigDecimal("2"), 90, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, "token", Instant.parse("2026-08-12T05:39:00Z"), Instant.parse("2026-08-12T06:39:00Z"));
        order.applyMarginAmount(new BigDecimal("12.60"));
        return PendingOrderEntity.from(order, "local-admin");
    }

    private static TradeOrderEntity protection(String role, String price, String size) {
        TradeOrderEntity row = new TradeOrderEntity();
        row.setUserName("local-admin"); row.setPendingOrderId(PENDING_ID); row.setOrderRole(role);
        row.setInstId("ETH-USDT-SWAP"); row.setSide("sell"); row.setPosSide("long");
        row.setOrdType("conditional"); row.setTdMode("isolated"); row.setSize(new BigDecimal(size));
        row.setPrice(new BigDecimal(price)); row.setReduceOnly(true); row.setStatus("PROTECTION_SUBMITTED");
        row.setCreatedAt(Instant.parse("2026-08-12T05:39:31Z")); row.setUpdatedAt(row.getCreatedAt());
        return row;
    }

    private static TradeFillEntity fill(String pendingOrderId, String side, String price, String size, String time) {
        TradeFillEntity row = new TradeFillEntity();
        row.setUserName("local-admin"); row.setPendingOrderId(pendingOrderId); row.setInstId("ETH-USDT-SWAP");
        row.setSide(side); row.setPosSide("long"); row.setFillPrice(new BigDecimal(price));
        row.setFillSize(new BigDecimal(size)); row.setFillTime(Instant.parse(time));
        return row;
    }

    private static TradeEventEntity event(TradeEventType type) {
        TradeEventEntity event = new TradeEventEntity();
        event.setUserName("local-admin"); event.setPendingOrderId(PENDING_ID);
        event.setInstId("ETH-USDT-SWAP"); event.setEventType(type.name());
        event.setCreatedAt(Instant.parse("2026-08-12T05:39:32Z"));
        return event;
    }

    private record RendererFixture(TelegramMessageRenderer renderer, PendingOrderRepository pendingOrders,
                                   TradeOrderRepository tradeOrders, ClosePositionRecordRepository closes,
                                   TradeFillRepository fills, OkxInstrumentRulesProvider rules,
                                   AccountSnapshotService accounts, PositionSnapshotService positions) { }

    private static NotificationProperties enabledProperties() {
        NotificationProperties properties = new NotificationProperties();
        properties.setEnabled(true);
        properties.telegram().setEnabled(true);
        properties.telegram().setBotToken("token");
        properties.telegram().setChatId("123");
        return properties;
    }
}
