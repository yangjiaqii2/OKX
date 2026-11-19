package com.example.quant.agent.exit;

import com.example.quant.crypto.OkxRestClient;
import com.example.quant.okxtrade.OkxInstrumentRules;
import com.example.quant.order.PendingOrder;
import com.example.quant.tradeplan.TradeIntent;
import com.example.quant.tradeplan.TradePlanType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

/** Opt-in USDT linear swap policy. All rates use entry notional as their denominator. */
@Service
public class NetProfitExitPolicy {
    public static final String VERSION = "net-profit-v1";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal FIRST = new BigDecimal("0.003");
    private static final BigDecimal FINAL = new BigDecimal("0.01");
    private final OkxRestClient client;

    public NetProfitExitPolicy(OkxRestClient client) {
        this.client = client;
    }

    public static TradeIntent plan(TradeIntent base) {
        boolean shortSide = base.action() == TradePlanType.OPEN_SHORT;
        var first = leg(base, shortSide, FIRST, "TP1");
        var last = leg(base, shortSide, FINAL, "TP_FINAL");
        return new TradeIntent(base.planId(), base.instId(), base.action(), base.entryPolicy(),
                base.intendedEntryPrice(), base.initialStopLossPrice(), last.intendedTargetPrice(),
                base.intendedBaseQuantity(), base.riskBudget(), VERSION, List.of(first, last));
    }

    private static TradeIntent.ExitLeg leg(TradeIntent base, boolean shortSide, BigDecimal target, String id) {
        BigDecimal change = base.intendedEntryPrice().multiply(target);
        BigDecimal risk = base.intendedEntryPrice().subtract(base.initialStopLossPrice()).abs();
        BigDecimal px = shortSide ? base.intendedEntryPrice().subtract(change) : base.intendedEntryPrice().add(change);
        return new TradeIntent.ExitLeg(id, change.divide(risk, MC), px, HALF, "net return on entry notional");
    }

    public record FeeRates(BigDecimal maker, BigDecimal taker) {
        public BigDecimal roundTrip() { return maker.max(taker).add(taker); }
    }

    public FeeRates feeRates(String instId) {
        JsonNode instrument = client.publicGet("/api/v5/public/instruments?instType=SWAP&instId="
                + OkxRestClient.encode(instId)).path("data").path(0);
        String groupId = instrument.path("groupId").asText();
        JsonNode fees = client.privateGet("/api/v5/account/trade-fee?instType=SWAP").path("data").path(0);
        for (JsonNode group : fees.path("feeGroup")) {
            if (!groupId.isBlank() && groupId.equals(group.path("groupId").asText())) {
                return new FeeRates(cost(group, "maker"), cost(group, "taker"));
            }
        }
        if (!fees.path("feeGroup").isEmpty()) throw new IllegalStateException("SWAP_FEE_GROUP_UNKNOWN");
        return new FeeRates(cost(fees, "makerU"), cost(fees, "takerU"));
    }

    private static BigDecimal cost(JsonNode row, String key) {
        return number(row, key).negate().max(BigDecimal.ZERO);
    }

    public record Quote(BigDecimal limit, BigDecimal trigger) {}

    public Quote quote(PendingOrder order, OkxInstrumentRules rules, BigDecimal quantity, String role, Instant now) {
        String id = OkxRestClient.encode(order.instId());
        boolean shortSide = order.action() == TradePlanType.OPEN_SHORT;
        JsonNode positions = client.privateGet("/api/v5/account/positions?instId=" + id).path("data");
        JsonNode matched = null;
        for (JsonNode row : positions) {
            if (!order.instId().equals(row.path("instId").asText()) || !order.tdMode().equals(row.path("mgnMode").asText())) continue;
            String side = row.path("posSide").asText();
            BigDecimal size = number(row, "pos");
            if (size.signum() == 0 || (!order.posSide().equals(side)
                    && !("net".equals(side) && (size.signum() < 0) == shortSide))) continue;
            if (matched != null) throw new IllegalStateException("AMBIGUOUS_POSITION");
            matched = row;
        }
        if (matched == null || rules.tickSize().signum() <= 0 || !order.instId().endsWith("-USDT-SWAP")
                || !order.instId().startsWith(rules.contractValueCurrency() + "-")) {
            throw new IllegalStateException("NET_RETURN_FACTS_UNAVAILABLE");
        }
        BigDecimal entry = number(matched, "avgPx");
        BigDecimal units = number(matched, "pos").abs().multiply(rules.contractValue());
        BigDecimal paid = number(matched, "fee").add(number(matched, "fundingFee")).negate().max(BigDecimal.ZERO);
        BigDecimal taker = feeRates(order.instId()).taker();
        BigDecimal target = "TP1".equals(role) ? FIRST : "TP_FINAL".equals(role) ? FINAL : BigDecimal.ZERO;
        BigDecimal limit = targetPrice(entry, paid.divide(units, MC), taker, target, shortSide, rules.tickSize());
        JsonNode book = client.publicGet("/api/v5/market/books?instId=" + id + "&sz=50").path("data").path(0);
        long age = now.toEpochMilli() - book.path("ts").asLong();
        if (age < -2000 || age > 10000) throw new IllegalStateException("STALE_EXIT_BOOK");
        JsonNode levels = book.path(shortSide ? "asks" : "bids");
        BigDecimal impact = bookImpact(levels, quantity);
        BigDecimal trigger = shortSide ? limit.subtract(impact) : limit.add(impact);
        return new Quote(limit, round(trigger, rules.tickSize(), shortSide));
    }

    static BigDecimal targetPrice(BigDecimal entry, BigDecimal cost, BigDecimal taker, BigDecimal target,
                                  boolean shortSide, BigDecimal tick) {
        BigDecimal gain = entry.multiply(target).add(cost);
        BigDecimal px = shortSide ? entry.subtract(gain).divide(BigDecimal.ONE.add(taker), MC)
                : entry.add(gain).divide(BigDecimal.ONE.subtract(taker), MC);
        if (px.signum() <= 0) throw new IllegalStateException("INVALID_NET_TARGET");
        return round(px, tick, shortSide);
    }

    private static BigDecimal round(BigDecimal px, BigDecimal tick, boolean shortSide) {
        return px.divide(tick, 0, shortSide ? RoundingMode.FLOOR : RoundingMode.CEILING).multiply(tick);
    }

    static BigDecimal bookImpact(JsonNode levels, BigDecimal quantity) {
        BigDecimal remaining = quantity;
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode level : levels) {
            BigDecimal px = new BigDecimal(level.path(0).asText());
            BigDecimal size = new BigDecimal(level.path(1).asText());
            if (px.signum() <= 0 || size.signum() <= 0) throw new IllegalStateException("INVALID_BOOK_LEVEL");
            BigDecimal take = remaining.min(size);
            total = total.add(px.multiply(take));
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) return total.divide(quantity, MC)
                    .subtract(new BigDecimal(levels.path(0).path(0).asText())).abs();
        }
        throw new IllegalStateException("INSUFFICIENT_EXIT_DEPTH");
    }

    public String exitReason(PendingOrder order, Instant start, Instant now, boolean firstFilled) {
        JsonNode data = client.publicGet("/api/v5/market/candles?instId=" + OkxRestClient.encode(order.instId())
                + "&bar=5m&limit=30").path("data");
        return exitReason(data, order.action() == TradePlanType.OPEN_SHORT, start, now, firstFilled);
    }

    static String exitReason(JsonNode data, boolean shortSide, Instant start, Instant now, boolean firstFilled) {
        long bar = 300000;
        List<BigDecimal> closes = new ArrayList<>();
        long expected = Math.floorDiv(now.toEpochMilli(), bar) * bar - bar;
        int held = 0;
        for (JsonNode row : data) {
            if (!"1".equals(row.path(8).asText())) continue;
            long ts = row.path(0).asLong();
            if (ts != expected) return null;
            BigDecimal close = new BigDecimal(row.path(4).asText());
            if (close.signum() <= 0) return null;
            closes.add(close);
            if (ts >= start.toEpochMilli()) held++;
            expected -= bar;
        }
        if (!firstFilled && held >= 6) return "SIX_BARS_WITHOUT_FIRST_EXIT";
        if (held < 2 || closes.size() < 22) return null;
        Collections.reverse(closes);
        BigDecimal ema = closes.subList(0, 20).stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(20), MC);
        int weak = 0;
        for (int i = 20; i < closes.size(); i++) {
            BigDecimal close = closes.get(i);
            BigDecimal next = ema.add(close.subtract(ema).multiply(BigDecimal.valueOf(2))
                    .divide(BigDecimal.valueOf(21), MC));
            boolean strong = shortSide ? close.compareTo(next) < 0 && next.compareTo(ema) < 0
                    : close.compareTo(next) > 0 && next.compareTo(ema) > 0;
            weak = strong ? 0 : weak + 1;
            ema = next;
        }
        return weak >= 2 ? "TWO_WEAK_EMA20_BARS" : null;
    }

    private static BigDecimal number(JsonNode row, String key) {
        return new BigDecimal(row.path(key).asText());
    }
}
