package com.example.quant.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxOrderGateway;
import com.example.quant.okxtrade.OkxTradeAdapter;
import com.example.quant.risk.ContractRiskRequest;
import com.example.quant.risk.RiskCheckResult;
import com.example.quant.risk.RiskLevel;
import com.example.quant.risk.RiskService;
import com.example.quant.risk.ContractRiskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderConfirmServiceTest {

    @Test
    void expiredPendingOrderCannotBeConfirmed() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(1, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        Clock later = Clock.offset(clock, Duration.ofSeconds(2));
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                later
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isFalse();
        assertThat(result.message()).contains("过期");
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void confirmationPlacesRealOrder() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                new ContractRiskService(),
                new OkxTradeAdapter(new SuccessfulGateway()),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.TEN, ContractRiskRequest.safeDefaults());

        assertThat(result.executed()).isTrue();
        assertThat(result.liveOrder()).isTrue();
        assertThat(result.externalOrderId()).isEqualTo("okx-order-1");
        assertThat(pendingOrderService.get(order.id()).status()).isEqualTo(OrderStatus.EXECUTED);
    }

    @Test
    void confirmationBuildsRiskRequestFromPendingOrderAndMargin() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-14T00:00:00Z"), ZoneOffset.UTC);
        PendingOrderService pendingOrderService = new PendingOrderService(120, clock);
        PendingOrder order = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, PendingOrderServiceTest.samplePlan());
        CapturingRiskService riskService = new CapturingRiskService();
        OrderConfirmService confirmService = new OrderConfirmService(
                pendingOrderService,
                riskService,
                new OkxTradeAdapter(new SuccessfulGateway()),
                clock
        );

        OrderExecutionResult result = confirmService.confirm(order.id(), BigDecimal.valueOf(75));

        assertThat(result.executed()).isTrue();
        assertThat(riskService.captured).isNotNull();
        assertThat(riskService.captured.leverage()).isEqualTo(order.leverage());
        assertThat(riskService.captured.entryPrice()).isEqualByComparingTo(order.price());
        assertThat(riskService.captured.stopLossPrice()).isEqualByComparingTo(order.stopLossPrice());
        assertThat(riskService.captured.riskRewardRatio()).isEqualByComparingTo(order.riskRewardRatio());
        assertThat(riskService.captured.suggestedMargin()).isEqualByComparingTo("75");
        assertThat(riskService.captured.signalScore()).isEqualTo(order.signalScore());
        assertThat(riskService.captured.volatility()).isEqualByComparingTo(order.volatility());
        assertThat(riskService.captured.fundingRate()).isEqualByComparingTo(order.fundingRate());
        assertThat(riskService.captured.volume24h()).isEqualByComparingTo(order.volume24h());
    }

    private static class SuccessfulGateway implements OkxOrderGateway {
        @Override
        public JsonNode placeOrder(Map<String, String> payload) {
            ObjectNode root = new ObjectMapper().createObjectNode();
            root.putArray("data").addObject().put("ordId", "okx-order-1");
            return root;
        }
    }

    private static class CapturingRiskService implements RiskService {
        private ContractRiskRequest captured;

        @Override
        public RiskCheckResult check(ContractRiskRequest request) {
            this.captured = request;
            return new RiskCheckResult(true, RiskLevel.LOW, null, null, java.util.List.of(), null,
                    request.leverage(), BigDecimal.valueOf(100), Instant.now());
        }
    }
}
