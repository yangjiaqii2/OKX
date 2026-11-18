package com.example.quant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class MarketDataContextTest {
    @Test
    void demoExecutionWithPublicSignalIsExplicit() {
        MarketDataContext context = MarketDataContext.resolve(TradingMode.DEMO, "PUBLIC");
        assertThat(context.signalVenue()).isEqualTo(MarketDataVenue.PUBLIC_LIVE);
        assertThat(context.executionVenue()).isEqualTo(MarketDataVenue.DEMO);
        assertThat(context.instrumentVenue()).isEqualTo(MarketDataVenue.PUBLIC_LIVE);
    }

    @Test
    void liveExecutionUsesPublicLiveSignal() {
        MarketDataContext context = MarketDataContext.resolve(TradingMode.LIVE, "PUBLIC");
        assertThat(context.signalVenue()).isEqualTo(MarketDataVenue.PUBLIC_LIVE);
        assertThat(context.executionVenue()).isEqualTo(MarketDataVenue.LIVE);
    }

    @Test
    void unavailableDemoPublicMarketModeCannotBeClaimedAsProvenance() {
        assertThatThrownBy(() -> MarketDataContext.resolve(TradingMode.DEMO, "DEMO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUBLIC_LIVE");
    }
}
