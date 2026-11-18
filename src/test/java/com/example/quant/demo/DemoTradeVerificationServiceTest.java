package com.example.quant.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.order.OrderConfirmService;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.risk.RiskLevel;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DemoTradeVerificationServiceTest {

    @Test
    void submitsFixedSmallDemoIntentThroughExistingOrderConfirmation() {
        ContractAnalysisService analysis = mock(ContractAnalysisService.class);
        PendingOrderService pending = mock(PendingOrderService.class);
        OrderConfirmService confirm = mock(OrderConfirmService.class);
        PositionSnapshotService positions = mock(PositionSnapshotService.class);
        OkxCurrentOrderService currentOrders = mock(OkxCurrentOrderService.class);
        ContractCandidate candidate = candidate();
        PendingOrder order = mock(PendingOrder.class);
        UUID pendingId = UUID.randomUUID();
        when(analysis.candidates()).thenReturn(List.of(candidate));
        when(positions.positions()).thenReturn(List.of());
        when(currentOrders.currentOrders()).thenReturn(List.of());
        when(order.id()).thenReturn(pendingId);
        when(order.clientOrderId()).thenReturn("qademoverify");
        when(pending.createPendingOrder(eq(MarketType.OKX_SWAP), any())).thenReturn(order);
        when(confirm.confirm(pendingId, new BigDecimal("5")))
                .thenReturn(OrderExecutionResult.submitted("okx-1", "submitted"));
        DemoTradeVerificationService service = new DemoTradeVerificationService(
                new TradingProperties(TradingMode.DEMO, true, 120, false), analysis, pending, confirm,
                positions, currentOrders);

        DemoTradeVerificationService.Result result = service.submit("ETH-USDT-SWAP");

        ArgumentCaptor<TradePlan> plan = ArgumentCaptor.forClass(TradePlan.class);
        verify(pending).createPendingOrder(eq(MarketType.OKX_SWAP), plan.capture());
        assertThat(plan.getValue().action()).isEqualTo(TradePlanType.OPEN_LONG);
        assertThat(plan.getValue().orderType()).isEqualTo("MARKET");
        assertThat(plan.getValue().suggestedLeverage()).isEqualTo(1);
        assertThat(plan.getValue().stopLossPrice()).isLessThan(plan.getValue().entryPrice());
        assertThat(result.okxOrderId()).isEqualTo("okx-1");
        verify(analysis, never()).refreshCandidates();
    }

    @Test
    void refusesVerificationIntentOutsideDemo() {
        OrderConfirmService confirm = mock(OrderConfirmService.class);
        DemoTradeVerificationService service = new DemoTradeVerificationService(
                new TradingProperties(TradingMode.LIVE, true, 120, false),
                mock(ContractAnalysisService.class), mock(PendingOrderService.class), confirm,
                mock(PositionSnapshotService.class), mock(OkxCurrentOrderService.class));

        assertThatThrownBy(() -> service.submit("ETH-USDT-SWAP"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("仅允许DEMO");
        verify(confirm, never()).confirm(any(), any());
    }

    private static ContractCandidate candidate() {
        return new ContractCandidate(
                MarketType.OKX_SWAP, "ETH-USDT-SWAP", "ETH", "USDT", new BigDecimal("2000"),
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("100000000"), BigDecimal.ONE,
                new BigDecimal("0.0001"), BigDecimal.ZERO, BigDecimal.ZERO, DirectionBias.BULLISH,
                new BigDecimal("0.02"), 80, new ContractFactorScore(20, 15, 10, 10, 10, 10, 7),
                new BigDecimal("2000"), new BigDecimal("1980"), new BigDecimal("2040"), 1,
                BigDecimal.valueOf(2), BigDecimal.ONE, new BigDecimal("100000"), new BigDecimal("100000"),
                BigDecimal.ONE, DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW,
                List.of(), List.of(), Instant.now());
    }
}
