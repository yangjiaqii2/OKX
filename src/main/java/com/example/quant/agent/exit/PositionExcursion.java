package com.example.quant.agent.exit;

import com.example.quant.tradeplan.TradePlanType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * Maximum Favourable / Adverse Excursion of one position, expressed both in
 * price, percent and R.
 *
 * <p>R is always derived from THIS trade's own initial risk
 * {@code |entryPrice - initialStopLoss|}. A 10% price move is not "+1R"; R only
 * means something relative to the risk that was taken on entry.
 *
 * <p>Immutable. Every observation returns a new instance, which keeps the
 * tracker pure and trivially replayable for historical simulation.
 */
public record PositionExcursion(
        BigDecimal highestSinceEntry,
        BigDecimal lowestSinceEntry,
        BigDecimal mfePrice,
        BigDecimal maePrice,
        BigDecimal mfePct,
        BigDecimal maePct,
        BigDecimal mfeR,
        BigDecimal maeR,
        Instant mfeAt,
        Instant maeAt
) {
    private static final int SCALE = 8;

    public static PositionExcursion seed(TradeSide side, BigDecimal entryPrice, BigDecimal initialRisk,
                                         BigDecimal price, Instant observedAt) {
        Objects.requireNonNull(side, "side");
        if (entryPrice == null || entryPrice.signum() <= 0) {
            throw new IllegalArgumentException("EXIT_FACTS_REQUIRE_POSITIVE_ENTRY");
        }
        if (initialRisk == null || initialRisk.signum() <= 0) {
            throw new IllegalArgumentException("EXIT_FACTS_REQUIRE_POSITIVE_INITIAL_RISK");
        }
        PositionExcursion empty = new PositionExcursion(null, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
        return empty.observe(side, entryPrice, initialRisk, price, observedAt);
    }

    /**
     * Folds one observed price into the excursion. Favourable and adverse sides
     * are mirrored for LONG/SHORT.
     */
    public PositionExcursion observe(TradeSide side, BigDecimal entryPrice, BigDecimal initialRisk,
                                     BigDecimal price, Instant observedAt) {
        Objects.requireNonNull(side, "side");
        if (price == null || price.signum() <= 0 || entryPrice == null || entryPrice.signum() <= 0
                || initialRisk == null || initialRisk.signum() <= 0) {
            return this;
        }
        BigDecimal highest = highestSinceEntry == null ? price : highestSinceEntry.max(price);
        BigDecimal lowest = lowestSinceEntry == null ? price : lowestSinceEntry.min(price);

        BigDecimal favorablePrice = side == TradeSide.SHORT ? lowest : highest;
        BigDecimal adversePrice = side == TradeSide.SHORT ? highest : lowest;

        BigDecimal mfe = side.signed(entryPrice, favorablePrice);
        BigDecimal mae = side.signed(adversePrice, entryPrice);
        if (mfe.signum() < 0) {
            mfe = BigDecimal.ZERO;
        }
        if (mae.signum() < 0) {
            mae = BigDecimal.ZERO;
        }

        BigDecimal previousMfe = this.mfeR == null ? BigDecimal.ZERO : this.mfeR;
        BigDecimal previousMae = this.maeR == null ? BigDecimal.ZERO : this.maeR;
        BigDecimal mfeR = mfe.divide(initialRisk, SCALE, RoundingMode.HALF_UP);
        BigDecimal maeR = mae.divide(initialRisk, SCALE, RoundingMode.HALF_UP);

        BigDecimal mfePct = pct(mfe, entryPrice);
        BigDecimal maePct = pct(mae, entryPrice);

        // Extremes are monotone: only replace the timestamp when the extreme itself improved.
        Instant mfeAt = mfeR.compareTo(previousMfe) > 0 ? observedAt : this.mfeAt;
        Instant maeAt = maeR.compareTo(previousMae) > 0 ? observedAt : this.maeAt;

        return new PositionExcursion(highest, lowest, favorablePrice, adversePrice,
                mfePct, maePct, mfeR.max(BigDecimal.ZERO), maeR.max(BigDecimal.ZERO), mfeAt, maeAt);
    }

    public BigDecimal mfePriceOrZero() {
        return mfePrice == null ? BigDecimal.ZERO : mfePrice;
    }

    public BigDecimal mfeROrZero() {
        return mfeR == null ? BigDecimal.ZERO : mfeR;
    }

    public BigDecimal maeROrZero() {
        return maeR == null ? BigDecimal.ZERO : maeR;
    }

    private static BigDecimal pct(BigDecimal move, BigDecimal entryPrice) {
        return move.multiply(BigDecimal.valueOf(100)).divide(entryPrice, SCALE, RoundingMode.HALF_UP);
    }

    /** Direction-aware helper so LONG/SHORT never need separate branches. */
    public enum TradeSide {
        LONG,
        SHORT;

        public static TradeSide from(TradePlanType action) {
            return action == TradePlanType.OPEN_SHORT ? SHORT : LONG;
        }

        /** Signed distance: positive when {@code to} is favourable relative to {@code from}. */
        public BigDecimal signed(BigDecimal from, BigDecimal to) {
            return this == SHORT ? from.subtract(to) : to.subtract(from);
        }

        /** Price {@code distance} (in R multiples of risk) away from {@code entry}. */
        public BigDecimal priceAt(BigDecimal entry, BigDecimal risk, BigDecimal rMultiple) {
            return this == SHORT ? entry.subtract(risk.multiply(rMultiple)) : entry.add(risk.multiply(rMultiple));
        }
    }
}
