package com.example.quant.agent.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.quant.crypto.OkxMarketService;
import com.example.quant.risk.RiskLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketEnvironmentServiceTest {

    @Test
    void insufficientBenchmarkCandlesRemainUnavailableInsteadOfFlatLowRisk() {
        OkxMarketService marketService = mock(OkxMarketService.class);
        when(marketService.candles(anyString(), anyString(), anyInt())).thenReturn(List.of());

        MarketEnvironment environment = new MarketEnvironmentService(marketService).current();

        assertThat(environment.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(environment.btcReturn20m()).isNull();
        assertThat(environment.btcReturn1h()).isNull();
        assertThat(environment.ethReturn20m()).isNull();
        assertThat(environment.ethReturn1h()).isNull();
        assertThat(environment.reasonList()).anyMatch(reason -> reason.contains("数据不足"));
    }
}
