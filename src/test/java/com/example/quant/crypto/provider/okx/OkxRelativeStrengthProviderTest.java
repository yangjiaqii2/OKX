package com.example.quant.crypto.provider.okx;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import com.example.quant.crypto.dto.ContractKlineSet;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OkxRelativeStrengthProviderTest {
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("基准与候选请求至少 48 根 1H K 线，不能只用 24 根")
    void requestsEnoughHourlyCandlesForTwentyFourHourWindow() {
        AtomicInteger requestedLimit = new AtomicInteger();
        OkxMarketService market = new OkxMarketService(null) {
            @Override
            public List<ContractCandle> candles(String instId, String bar, int limit, Duration timeout) {
                requestedLimit.set(limit);
                return hourly(limit);
            }
        };
        OkxRelativeStrengthProvider provider = new OkxRelativeStrengthProvider(market, 60, 600);
        ContractKlineSet emptyHour = new ContractKlineSet(List.of(), List.of(), List.of(), List.of(), List.of());

        provider.fetch("X-USDT-SWAP", emptyHour);

        assertThat(requestedLimit.get()).isGreaterThanOrEqualTo(25);
        assertThat(requestedLimit.get()).isEqualTo(OkxRelativeStrengthProvider.HOURLY_CANDLE_LIMIT);
        assertThat(OkxRelativeStrengthProvider.HOURLY_CANDLE_LIMIT).isGreaterThan(24);
    }

    @Test
    @DisplayName("ProviderResult 与 snapshot 数据质量一致：窗口不足时外层不得标 FRESH")
    void providerResultQualityMatchesSnapshotWhenWindowUnavailable() {
        OkxMarketService market = new OkxMarketService(null) {
            @Override
            public List<ContractCandle> candles(String instId, String bar, int limit, Duration timeout) {
                return hourly(24);
            }
        };
        OkxRelativeStrengthProvider provider = new OkxRelativeStrengthProvider(market, 60, 600);
        ContractKlineSet existing = new ContractKlineSet(List.of(), List.of(), hourly(24), List.of(), List.of());

        ProviderResult<RelativeStrengthSnapshot> result = provider.fetch("X-USDT-SWAP", existing);

        assertThat(result.dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(result.data().dataQuality()).isEqualTo(DataQuality.UNAVAILABLE);
        assertThat(result.data().rs24h()).isNull();
    }

    private static List<ContractCandle> hourly(int count) {
        List<ContractCandle> candles = new ArrayList<>(count);
        for (int hour = 0; hour < count; hour++) {
            BigDecimal close = BigDecimal.valueOf(100 + hour);
            candles.add(new ContractCandle(BASE.plusSeconds(hour * 3600L),
                    close, close, close, close, BigDecimal.ONE));
        }
        return candles;
    }
}
