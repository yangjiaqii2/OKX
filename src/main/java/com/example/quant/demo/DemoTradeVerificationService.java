package com.example.quant.demo;

import com.example.quant.account.PositionSnapshotService;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.okxtrade.OkxCurrentOrderService;
import com.example.quant.order.OrderConfirmService;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.order.OrderStatus;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DemoTradeVerificationService {
    private static final BigDecimal MARGIN = new BigDecimal("5");
    private static final BigDecimal STOP_RATE = new BigDecimal("0.01");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final TradingProperties trading;
    private final ContractAnalysisService analysis;
    private final PendingOrderService pendingOrders;
    private final OrderConfirmService orderConfirm;
    private final PositionSnapshotService positions;
    private final OkxCurrentOrderService currentOrders;

    public DemoTradeVerificationService(TradingProperties trading, ContractAnalysisService analysis,
                                        PendingOrderService pendingOrders, OrderConfirmService orderConfirm,
                                        PositionSnapshotService positions, OkxCurrentOrderService currentOrders) {
        this.trading = trading;
        this.analysis = analysis;
        this.pendingOrders = pendingOrders;
        this.orderConfirm = orderConfirm;
        this.positions = positions;
        this.currentOrders = currentOrders;
    }

    public Result submit(String requestedInstId) {
        if (trading.mode() != TradingMode.DEMO) {
            throw new IllegalStateException("Demo验证委托仅允许DEMO模式");
        }
        String instId = normalizeInstId(requestedInstId);
        if (!positions.positions().isEmpty() || !currentOrders.currentOrders().isEmpty()) {
            throw new IllegalStateException("Demo验证前必须没有持仓和普通未成交订单");
        }
        boolean unresolvedSubmit = pendingOrders.allOrders().stream()
                .anyMatch(order -> order.status() == OrderStatus.SUBMITTING
                        || order.status() == OrderStatus.UNKNOWN_SUBMIT_STATUS);
        if (unresolvedSubmit) {
            throw new IllegalStateException("Demo验证前必须先恢复未决订单提交状态");
        }
        List<ContractCandidate> candidates = analysis.candidates();
        ContractCandidate candidate = candidates.stream()
                .filter(item -> instId.equals(item.instId()))
                .findFirst()
                .orElseGet(() -> analysis.refreshCandidates().stream()
                        .filter(item -> instId.equals(item.instId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("当前候选池没有合约：" + instId)));
        TradePlan plan = verificationPlan(candidate);
        PendingOrder pending = pendingOrders.createPendingOrder(MarketType.OKX_SWAP, plan);
        OrderExecutionResult result = orderConfirm.confirm(pending.id(), MARGIN);
        if (!result.submitted()) {
            throw new IllegalStateException("Demo验证委托未提交：" + result.message());
        }
        return new Result(pending.id(), pending.clientOrderId(), result.externalOrderId(), result.message());
    }

    private static TradePlan verificationPlan(ContractCandidate candidate) {
        BigDecimal entry = candidate.lastPrice();
        if (entry == null || entry.signum() <= 0) {
            throw new IllegalStateException("Demo验证行情价格不可用");
        }
        boolean shortSide = candidate.trendDirection() == DirectionBias.BEARISH;
        BigDecimal distance = entry.multiply(STOP_RATE);
        BigDecimal stop = shortSide ? entry.add(distance) : entry.subtract(distance);
        BigDecimal takeProfit = shortSide ? entry.subtract(distance.multiply(TWO))
                : entry.add(distance.multiply(TWO));
        return new TradePlan(
                UUID.randomUUID(), candidate.instId(), shortSide ? TradePlanType.OPEN_SHORT : TradePlanType.OPEN_LONG,
                "MARKET", shortSide ? DirectionBias.BEARISH : DirectionBias.BULLISH, BigDecimal.ONE,
                entry, stop, takeProfit, 1, MARGIN.divide(entry, 8, RoundingMode.DOWN), MARGIN,
                MARGIN.multiply(STOP_RATE), TWO, 100,
                value(candidate.fundingRate()), value(candidate.volatility()), value(candidate.volume24h()), "cross",
                List.of("仅用于OKX Demo交易基础设施E2E验证，不代表策略信号"),
                List.of("固定5 USDT保证金、1倍杠杆、1%止损；LIVE模式不可调用"),
                "验证完成后必须通过系统平仓", true, Instant.now().plusSeconds(120));
    }

    private static String normalizeInstId(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]+-USDT-SWAP")) {
            throw new IllegalArgumentException("Demo验证只支持USDT SWAP合约");
        }
        return normalized;
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record Result(UUID pendingOrderId, String clientOrderId, String okxOrderId, String message) { }
}
