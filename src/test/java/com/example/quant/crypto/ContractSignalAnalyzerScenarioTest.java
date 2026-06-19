package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.dto.ContractSignal;
import com.example.quant.crypto.dto.ContractSignalType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractSignalAnalyzerScenarioTest {

    private final ContractSignalAnalyzer analyzer = new ContractSignalAnalyzer();

    @Test
    void classifiesHealthyTwentyMinuteTrendAsStrongLong() {
        ContractSignal signal = analyzer.analyze(
                "TREND-USDT-SWAP",
                new BigDecimal("105"),
                new BigDecimal("100"),
                new BigDecimal("900000000"),
                new BigDecimal("0.0002"),
                new BigDecimal("1000000"),
                new BigDecimal("940000"),
                klineSet(upTrend20m(new BigDecimal("100"), new BigDecimal("0.085"), false),
                        pullbackReady5m(true), upTrend20m(new BigDecimal("98"), new BigDecimal("0.12"), false),
                        upTrend20m(new BigDecimal("90"), new BigDecimal("0.18"), false))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.STRONG_LONG);
        assertThat(signal.action()).isEqualTo("AUTO_TRADE_ALLOWED");
        assertThat(signal.klineAnalysis().trend20m()).isEqualTo("UP");
        assertThat(signal.klineAnalysis().entryTiming5m()).isEqualTo("READY");
        assertThat(signal.scoreBreakdown().trend()).isGreaterThanOrEqualTo(80);
        assertThat(signal.score()).isGreaterThanOrEqualTo(80);
    }

    @Test
    void classifiesWarmButValidTrendAsPullbackLongOrWaitConfirm() {
        ContractSignal signal = analyzer.analyze(
                "WARM-USDT-SWAP",
                new BigDecimal("112"),
                new BigDecimal("100"),
                new BigDecimal("900000000"),
                new BigDecimal("0.0002"),
                new BigDecimal("1000000"),
                new BigDecimal("980000"),
                klineSet(upTrend20m(new BigDecimal("100"), new BigDecimal("0.18"), false),
                        pullbackReady5m(true), upTrend20m(new BigDecimal("99"), new BigDecimal("0.14"), false),
                        range20m(new BigDecimal("100")))
        );

        assertThat(signal.signalType()).isIn(ContractSignalType.PULLBACK_LONG, ContractSignalType.STRONG_LONG);
        assertThat(signal.action()).isIn("AUTO_TRADE_ALLOWED", "WAIT_CONFIRM");
        assertThat(signal.suggestedLeverage()).isLessThanOrEqualTo(4);
    }

    @Test
    void blocksOverheatedPumpFromBecomingTradableLong() {
        ContractSignal signal = analyzer.analyze(
                "HOT-USDT-SWAP",
                new BigDecimal("120"),
                new BigDecimal("100"),
                new BigDecimal("1200000000"),
                new BigDecimal("0.0011"),
                new BigDecimal("1000000"),
                new BigDecimal("800000"),
                klineSet(upTrend20m(new BigDecimal("95"), new BigDecimal("0.45"), true),
                        vertical5m(true), upTrend20m(new BigDecimal("90"), new BigDecimal("0.35"), false),
                        upTrend20m(new BigDecimal("80"), new BigDecimal("0.40"), false))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.WAIT_OVERHEATED);
        assertThat(signal.action()).isEqualTo("WAIT");
        assertThat(signal.suggestedLeverage()).isZero();
        assertThat(signal.riskTagList()).anyMatch(risk -> risk.contains("追高") || risk.contains("过热"));
    }

    @Test
    void highTodayGainAndSharpDropWithoutStructureBreakCanOnlyWatch() {
        ContractSignal signal = analyzer.analyze(
                "PUMP-WATCH-USDT-SWAP",
                new BigDecimal("180"),
                new BigDecimal("100"),
                new BigDecimal("1200000000"),
                new BigDecimal("0.0012"),
                new BigDecimal("1000000"),
                new BigDecimal("900000"),
                klineSet(upTrend20m(new BigDecimal("170"), new BigDecimal("0.18"), true),
                        sharpDrop5m(), range20m(new BigDecimal("180")), range20m(new BigDecimal("180")))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.WAIT_OVERHEATED);
        assertThat(signal.action()).isEqualTo("WAIT");
        assertThat(signal.suggestedLeverage()).isZero();
    }

    @Test
    void highTodayGainAndSharpDropWithStructureBreakAllowsReversalShortCandidate() {
        ContractSignal signal = analyzer.analyze(
                "PUMP-SHORT-USDT-SWAP",
                new BigDecimal("180"),
                new BigDecimal("100"),
                new BigDecimal("1200000000"),
                new BigDecimal("0.0012"),
                new BigDecimal("1200000"),
                new BigDecimal("900000"),
                klineSet(reversalBreakAfterPump20m(), sharpDrop5m(),
                        range20m(new BigDecimal("180")), range20m(new BigDecimal("180")))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.REVERSAL_SHORT);
        assertThat(signal.directionBias().name()).isEqualTo("BEARISH");
        assertThat(signal.action()).isIn("AUTO_TRADE_ALLOWED", "WAIT_CONFIRM");
        assertThat(signal.stopLossPrice()).isGreaterThan(signal.entryPrice());
    }

    @Test
    void identifiesHighLevelTwentyMinuteBreakAsReversalShort() {
        ContractSignal signal = analyzer.analyze(
                "REV-USDT-SWAP",
                new BigDecimal("115"),
                new BigDecimal("100"),
                new BigDecimal("800000000"),
                new BigDecimal("0.0004"),
                new BigDecimal("1000000"),
                new BigDecimal("900000"),
                klineSet(reversalShort20m(), pullbackReady5m(false),
                        upTrend20m(new BigDecimal("100"), new BigDecimal("0.10"), false),
                        range20m(new BigDecimal("105")))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.REVERSAL_SHORT);
        assertThat(signal.directionBias().name()).isEqualTo("BEARISH");
        assertThat(signal.suggestedLeverage()).isLessThanOrEqualTo(3);
        assertThat(signal.stopLossPrice()).isGreaterThan(signal.entryPrice());
    }

    @Test
    void classifiesHealthyWeaknessAsTrendShort() {
        ContractSignal signal = analyzer.analyze(
                "SHORT-USDT-SWAP",
                new BigDecimal("94"),
                new BigDecimal("100"),
                new BigDecimal("900000000"),
                new BigDecimal("-0.0002"),
                new BigDecimal("1000000"),
                new BigDecimal("920000"),
                klineSet(downTrend20m(new BigDecimal("100"), new BigDecimal("0.10"), false),
                        pullbackReady5m(false), downTrend20m(new BigDecimal("104"), new BigDecimal("0.12"), false),
                        range20m(new BigDecimal("102")))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.TREND_SHORT);
        assertThat(signal.action()).isEqualTo("AUTO_TRADE_ALLOWED");
        assertThat(signal.klineAnalysis().trend20m()).isEqualTo("DOWN");
        assertThat(signal.stopLossPrice()).isGreaterThan(signal.entryPrice());
    }

    @Test
    void waitsWhenShortSetupIsOversold() {
        ContractSignal signal = analyzer.analyze(
                "DUMP-USDT-SWAP",
                new BigDecimal("82"),
                new BigDecimal("100"),
                new BigDecimal("1200000000"),
                new BigDecimal("-0.0012"),
                new BigDecimal("1000000"),
                new BigDecimal("800000"),
                klineSet(downTrend20m(new BigDecimal("106"), new BigDecimal("0.45"), true),
                        vertical5m(false), downTrend20m(new BigDecimal("108"), new BigDecimal("0.32"), false),
                        downTrend20m(new BigDecimal("110"), new BigDecimal("0.40"), false))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.WAIT_OVERSOLD);
        assertThat(signal.action()).isEqualTo("WAIT");
        assertThat(signal.suggestedLeverage()).isZero();
    }

    @Test
    void classifiesFlatRangeAsNeutral() {
        ContractSignal signal = analyzer.analyze(
                "FLAT-USDT-SWAP",
                new BigDecimal("100.5"),
                new BigDecimal("100"),
                new BigDecimal("900000000"),
                BigDecimal.ZERO,
                new BigDecimal("1000000"),
                new BigDecimal("1000000"),
                klineSet(range20m(new BigDecimal("100")), range5m(), range20m(new BigDecimal("100")),
                        range20m(new BigDecimal("100")))
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.NEUTRAL);
        assertThat(signal.action()).isEqualTo("WAIT");
        assertThat(signal.klineAnalysis().structure()).isEqualTo("RANGE");
    }

    @Test
    void rejectsCandidateWhenTwentyMinuteKlinesAreInsufficient() {
        ContractSignal signal = analyzer.analyze(
                "SHORTDATA-USDT-SWAP",
                new BigDecimal("105"),
                new BigDecimal("100"),
                new BigDecimal("900000000"),
                BigDecimal.ZERO,
                new BigDecimal("1000000"),
                new BigDecimal("1000000"),
                new ContractKlineSet(upTrend20m(new BigDecimal("100"), new BigDecimal("0.10"), false).subList(0, 20),
                        pullbackReady5m(true), range20m(new BigDecimal("100")), range20m(new BigDecimal("100")),
                        List.of())
        );

        assertThat(signal.signalType()).isEqualTo(ContractSignalType.NO_TRADE);
        assertThat(signal.action()).isEqualTo("NO_TRADE");
        assertThat(signal.riskTagList()).anyMatch(risk -> risk.contains("20m K线数量不足"));
    }

    private static ContractKlineSet klineSet(List<ContractCandle> twentyMinute, List<ContractCandle> fiveMinute,
                                             List<ContractCandle> oneHour, List<ContractCandle> fourHour) {
        return new ContractKlineSet(twentyMinute, fiveMinute, oneHour, fourHour, List.of());
    }

    private static List<ContractCandle> upTrend20m(BigDecimal start, BigDecimal step, boolean longUpperWick) {
        List<ContractCandle> candles = new ArrayList<>();
        BigDecimal close = start;
        for (int i = 0; i < 60; i++) {
            BigDecimal open = close;
            BigDecimal delta = i % 3 == 0 ? step.multiply(new BigDecimal("-0.65")) : step;
            close = close.add(delta);
            BigDecimal volume = BigDecimal.valueOf(i >= 56 ? 2600 : 1000 + i * 8L);
            candles.add(candle(open, close, volume, longUpperWick && i >= 56, false));
        }
        return candles;
    }

    private static List<ContractCandle> downTrend20m(BigDecimal start, BigDecimal step, boolean longLowerWick) {
        List<ContractCandle> candles = new ArrayList<>();
        BigDecimal close = start;
        for (int i = 0; i < 60; i++) {
            BigDecimal open = close;
            BigDecimal delta = i % 3 == 0 ? step.multiply(new BigDecimal("0.65")) : step.negate();
            close = close.add(delta);
            BigDecimal volume = BigDecimal.valueOf(i >= 56 ? 2600 : 1000 + i * 8L);
            candles.add(candle(open, close, volume, false, longLowerWick && i >= 56));
        }
        return candles;
    }

    private static List<ContractCandle> reversalShort20m() {
        List<ContractCandle> candles = upTrend20m(new BigDecimal("100"), new BigDecimal("0.22"), false);
        BigDecimal close = candles.get(candles.size() - 1).close();
        for (int i = candles.size() - 6; i < candles.size(); i++) {
            BigDecimal open = close;
            close = close.subtract(new BigDecimal("0.75"));
            candles.set(i, candle(open, close, BigDecimal.valueOf(3200 + i * 20L), true, false));
        }
        return candles;
    }

    private static List<ContractCandle> reversalBreakAfterPump20m() {
        List<ContractCandle> candles = new ArrayList<>();
        BigDecimal close = new BigDecimal("160");
        for (int i = 0; i < 20; i++) {
            BigDecimal open = close;
            close = close.add(new BigDecimal("1.00"));
            candles.add(candle(open, close, BigDecimal.valueOf(1200 + i * 10L), false, false));
        }
        close = new BigDecimal("192");
        for (int i = 20; i < 40; i++) {
            BigDecimal open = close;
            close = close.add(i % 2 == 0 ? new BigDecimal("0.70") : new BigDecimal("-0.15"));
            candles.add(candle(open, close, BigDecimal.valueOf(1500 + i * 10L), false, false));
        }
        close = new BigDecimal("198");
        for (int i = 40; i < 60; i++) {
            BigDecimal open = close;
            close = close.subtract(i >= 56 ? new BigDecimal("3.20") : new BigDecimal("0.55"));
            candles.add(candle(open, close, BigDecimal.valueOf(i >= 55 ? 4200 + i * 20L : 1800 + i * 10L),
                    i >= 56, false));
        }
        return candles;
    }

    private static List<ContractCandle> sharpDrop5m() {
        List<ContractCandle> candles = range20m(new BigDecimal("200")).subList(0, 37);
        List<ContractCandle> result = new ArrayList<>(candles);
        result.add(candle(new BigDecimal("200"), new BigDecimal("200"), new BigDecimal("1800"), false, false));
        result.add(candle(new BigDecimal("200"), new BigDecimal("190"), new BigDecimal("5200"), true, false));
        result.add(candle(new BigDecimal("190"), new BigDecimal("180"), new BigDecimal("6200"), true, false));
        return result;
    }

    private static List<ContractCandle> range20m(BigDecimal center) {
        List<ContractCandle> candles = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            BigDecimal open = center.add(BigDecimal.valueOf((i % 4) - 2).multiply(new BigDecimal("0.03")));
            BigDecimal close = center.add(BigDecimal.valueOf((i % 5) - 2).multiply(new BigDecimal("0.03")));
            candles.add(candle(open, close, BigDecimal.valueOf(1000 + i), false, false));
        }
        return candles;
    }

    private static List<ContractCandle> pullbackReady5m(boolean longSide) {
        return longSide
                ? upTrend20m(new BigDecimal("102"), new BigDecimal("0.035"), false).subList(0, 40)
                : downTrend20m(new BigDecimal("98"), new BigDecimal("0.035"), false).subList(0, 40);
    }

    private static List<ContractCandle> vertical5m(boolean up) {
        return up
                ? upTrend20m(new BigDecimal("110"), new BigDecimal("0.20"), true).subList(0, 40)
                : downTrend20m(new BigDecimal("90"), new BigDecimal("0.20"), true).subList(0, 40);
    }

    private static List<ContractCandle> range5m() {
        return range20m(new BigDecimal("100")).subList(0, 40);
    }

    private static ContractCandle candle(BigDecimal open, BigDecimal close, BigDecimal volume,
                                         boolean longUpperWick, boolean longLowerWick) {
        BigDecimal high = open.max(close).add(longUpperWick ? new BigDecimal("1.40") : new BigDecimal("0.16"));
        BigDecimal low = open.min(close).subtract(longLowerWick ? new BigDecimal("1.40") : new BigDecimal("0.16"));
        return new ContractCandle(Instant.now(), open, high, low, close, volume);
    }
}
