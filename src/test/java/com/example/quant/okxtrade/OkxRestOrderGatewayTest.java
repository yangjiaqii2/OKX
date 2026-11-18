package com.example.quant.okxtrade;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.PositionSummary;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.OkxRestClient;
import com.example.quant.system.TradingEngineState;
import com.example.quant.system.TradingReadinessService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OkxRestOrderGatewayTest {
    @Test
    void fallsBackToHistoricalFillsWhenRecentWindowIsEmpty() {
        OkxRestClient client = mock(OkxRestClient.class);
        OkxRestOrderGateway gateway = new OkxRestOrderGateway(client);
        Map<String, String> payload = Map.of(
                "instType", "SWAP",
                "instId", "XAU-USDT-SWAP",
                "ordId", "3826856731638407168"
        );
        String query = "?instType=SWAP&instId=XAU-USDT-SWAP&ordId=3826856731638407168";
        var recent = JsonNodeFactory.instance.objectNode()
                .set("data", JsonNodeFactory.instance.arrayNode());
        var historical = JsonNodeFactory.instance.objectNode()
                .set("data", JsonNodeFactory.instance.arrayNode()
                        .add(JsonNodeFactory.instance.objectNode().put("tradeId", "421495631")));
        when(client.privateGet("/api/v5/trade/fills" + query)).thenReturn(recent);
        when(client.privateGet("/api/v5/trade/fills-history" + query)).thenReturn(historical);

        assertThat(gateway.fills(payload)).isSameAs(historical);

        verify(client).privateGet("/api/v5/trade/fills" + query);
        verify(client).privateGet("/api/v5/trade/fills-history" + query);
    }

    @Test
    void gatewayWithoutExplicitLiveModeFailsClosedForEveryMutation() {
        OkxRestClient client = mock(OkxRestClient.class);
        OkxRestOrderGateway gateway = new OkxRestOrderGateway(client);

        assertDisabled(() -> gateway.setLeverage(Map.of()));
        assertDisabled(() -> gateway.placeOrder(Map.of()));
        assertDisabled(() -> gateway.placeAlgoOrder(Map.of()));
        assertDisabled(() -> gateway.cancelOrder(Map.of()));
        assertDisabled(() -> gateway.cancelAlgoOrder(Map.of()));
        assertDisabled(() -> gateway.closePosition(Map.of()));
        verifyNoInteractions(client);
    }

    @Test
    void readyAllowsCloseWhileDegradedStillRejectsOpen() {
        OkxRestClient client = mock(OkxRestClient.class);
        TradingReadinessService readiness = new TradingReadinessService();
        PositionSummary position = new PositionSummary(
                "BTC-USDT-SWAP", "long", "5", new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, "cross", BigDecimal.TEN, BigDecimal.ZERO,
                new BigDecimal("500"), BigDecimal.ONE, "OKX_DEMO", "MANAGED", "MATCHED_LOCAL_FILL");
        OkxRestOrderGateway gateway = gateway(client, readiness, TradingMode.DEMO, List.of(position));
        Map<String, String> close = closePayload();
        when(client.privatePost("/api/v5/trade/close-position", close))
                .thenReturn(JsonNodeFactory.instance.objectNode());
        readiness.update(TradingEngineState.READY, "READY");

        TradingOperationContext closeContext = TradingOperationContext.of(
                TradingOperationType.CLOSE_POSITION, "alice", "pending-1", "plan-1");
        AuthUserContext.runAs("alice", () -> gateway.closePosition(close, closeContext));

        verify(client).privatePost("/api/v5/trade/close-position", close);
        readiness.update(TradingEngineState.DEGRADED, "WS_NOT_READY");
        assertThatThrownBy(() -> gateway.placeOrder(Map.of(
                "instId", "BTC-USDT-SWAP", "side", "buy", "sz", "1", "clOrdId", "OPENstable")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("新增风险操作已阻断");
    }

    @Test
    void readyStillRejectsReductionWithoutManagedPositionProof() {
        OkxRestClient client = mock(OkxRestClient.class);
        TradingReadinessService readiness = new TradingReadinessService();
        OkxRestOrderGateway gateway = gateway(client, readiness, TradingMode.DEMO, List.of());
        readiness.update(TradingEngineState.READY, "READY");
        TradingOperationContext context = TradingOperationContext.of(
                TradingOperationType.CLOSE_POSITION, "alice", "pending-1", "plan-1");

        assertThatThrownBy(() -> AuthUserContext.runAs("alice", () -> gateway.closePosition(closePayload(), context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POSITION_NOT_CONFIRMED");
        verifyNoInteractions(client);
    }

    @Test
    void degradedAllowsOnlyVerifiedContextualRiskReduction() {
        OkxRestClient client = mock(OkxRestClient.class);
        TradingReadinessService readiness = new TradingReadinessService();
        PositionSummary position = new PositionSummary(
                "BTC-USDT-SWAP", "long", "5", new BigDecimal("100"), BigDecimal.ZERO,
                BigDecimal.ONE, BigDecimal.ONE, "cross", BigDecimal.TEN, BigDecimal.ZERO,
                new BigDecimal("500"), BigDecimal.ONE, "OKX_DEMO", "MANAGED", "MATCHED_LOCAL_FILL");
        OkxRestOrderGateway gateway = gateway(client, readiness, TradingMode.DEMO, List.of(position));
        readiness.update(TradingEngineState.DEGRADED, "WS_NOT_READY");
        Map<String, String> close = closePayload();
        when(client.privatePost("/api/v5/trade/close-position", close))
                .thenReturn(JsonNodeFactory.instance.objectNode());
        TradingOperationContext context = TradingOperationContext.of(
                TradingOperationType.CLOSE_POSITION, "alice", "pending-1", "plan-1");

        AuthUserContext.runAs("alice", () -> gateway.closePosition(close, context));

        verify(client).privatePost("/api/v5/trade/close-position", close);
    }

    private static OkxRestOrderGateway gateway(OkxRestClient client, TradingReadinessService readiness,
                                                TradingMode mode, List<PositionSummary> positions) {
        OkxRestOrderGateway gateway = new OkxRestOrderGateway(
                client, new TradingProperties(mode, true, 120, false));
        PositionSnapshotService snapshots = mock(PositionSnapshotService.class);
        when(snapshots.positions()).thenReturn(positions);
        gateway.configureTradingReadiness(readiness);
        gateway.configureExposureReductionGuard(new ExposureReductionGuard(
                snapshots, new TradingProperties(mode, true, 120, false)));
        return gateway;
    }

    private static Map<String, String> closePayload() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("instId", "BTC-USDT-SWAP");
        payload.put("posSide", "long");
        payload.put("mgnMode", "cross");
        payload.put("clOrdId", "CLOSEstable");
        return payload;
    }

    private static void assertDisabled(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实盘交易已禁用");
    }
}
