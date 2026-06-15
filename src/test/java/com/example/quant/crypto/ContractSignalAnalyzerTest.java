package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractSignalAnalyzerTest {

    @Test
    void buildsHighQualityLongSignalWithDynamicLeverageAndRiskPrices() {
        ContractSignalAnalyzer analyzer = new ContractSignalAnalyzer();
        List<ContractCandle> candles = risingCandles();

        ContractSignal signal = analyzer.analyze(
                "BTC-USDT-SWAP",
                BigDecimal.valueOf(112),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(2_400_000_000L),
                BigDecimal.valueOf(0.0001),
                BigDecimal.valueOf(980_000_000L),
                BigDecimal.valueOf(900_000_000L),
                candles
        );

        assertThat(signal.score()).isGreaterThanOrEqualTo(70);
        assertThat(signal.suggestedLeverage()).isBetween(2, 5);
        assertThat(signal.stopLossPrice()).isLessThan(signal.entryPrice());
        assertThat(signal.takeProfitPrice()).isGreaterThan(signal.entryPrice());
        assertThat(signal.riskRewardRatio()).isGreaterThanOrEqualTo(new BigDecimal("1.80"));
        assertThat(signal.reasonList()).anyMatch(reason -> reason.contains("EMA"));
        assertThat(signal.reasonList()).anyMatch(reason -> reason.contains("RSI"));
    }

    @Test
    void penalizesCrowdedVolatileFundingSetupAndReducesLeverage() {
        ContractSignalAnalyzer analyzer = new ContractSignalAnalyzer();
        List<ContractCandle> candles = whipsawCandles();

        ContractSignal signal = analyzer.analyze(
                "MEME-USDT-SWAP",
                BigDecimal.valueOf(93),
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(1_800_000_000L),
                BigDecimal.valueOf(0.004),
                BigDecimal.valueOf(1_300_000_000L),
                BigDecimal.valueOf(900_000_000L),
                candles
        );

        assertThat(signal.score()).isLessThan(65);
        assertThat(signal.suggestedLeverage()).isEqualTo(1);
        assertThat(signal.riskTagList()).contains("资金费率拥挤", "波动率过高");
    }

    private static List<ContractCandle> risingCandles() {
        List<ContractCandle> candles = new ArrayList<>();
        BigDecimal close = BigDecimal.valueOf(100);
        for (int i = 0; i < 60; i++) {
            BigDecimal open = close;
            close = close.add(BigDecimal.valueOf(i % 9 == 0 ? -0.18 : i < 40 ? 0.18 : 0.42));
            candles.add(candle(open, close, BigDecimal.valueOf(1000 + i * 8L)));
        }
        return candles;
    }

    private static List<ContractCandle> whipsawCandles() {
        List<ContractCandle> candles = new ArrayList<>();
        BigDecimal close = BigDecimal.valueOf(100);
        for (int i = 0; i < 60; i++) {
            BigDecimal open = close;
            close = close.add(BigDecimal.valueOf(i % 2 == 0 ? 5 : -6));
            candles.add(candle(open, close, BigDecimal.valueOf(1500 + (i % 8) * 300L)));
        }
        return candles;
    }

    private static ContractCandle candle(BigDecimal open, BigDecimal close, BigDecimal volume) {
        BigDecimal high = open.max(close).multiply(BigDecimal.valueOf(1.006));
        BigDecimal low = open.min(close).multiply(BigDecimal.valueOf(0.994));
        return new ContractCandle(Instant.now(), open, high, low, close, volume);
    }
}
