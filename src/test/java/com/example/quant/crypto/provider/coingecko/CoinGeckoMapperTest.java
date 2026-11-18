package com.example.quant.crypto.provider.coingecko;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CoinGeckoMapperTest {

    @Test
    void mapBtcRealSnapshotProducesFreshAndAllFields() {
        // 真实 CoinGecko /coins/markets 的 BTC 静态快照（脱机，仅用于解析契约测试）
        CoinGeckoMarketData data = CoinGeckoConstants.sampleBtc();
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("BTC", data);

        assertThat(result.isUsable()).isTrue();
        assertThat(result.quality()).isEqualTo(CoinGeckoMapper.Quality.FRESH);
        MarketCapSnapshot snap = result.snapshot();
        assertThat(snap.baseSymbol()).isEqualTo("BTC");
        assertThat(snap.marketCapUsd()).isEqualByComparingTo(new BigDecimal("1180000000000.5000"));
        assertThat(snap.fdvUsd()).isEqualByComparingTo(new BigDecimal("2100000000000.0000"));
        assertThat(snap.circulatingSupply()).isEqualByComparingTo(new BigDecimal("19740000.4200"));
        assertThat(snap.totalSupply()).isEqualByComparingTo(new BigDecimal("21000000.0000"));
        assertThat(snap.maxSupply()).isEqualByComparingTo(new BigDecimal("21000000.0000"));
        assertThat(snap.source()).isEqualTo("coingecko");
    }

    @Test
    void mapEthWithoutMaxSupplyFallsBackToTotalSupply() {
        // ETH 在 CoinGecko 实际响应中 max_supply = null；totalSupply 必须接管
        CoinGeckoMarketData data = CoinGeckoConstants.sampleEth();
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("ETH", data);

        assertThat(result.isUsable()).isTrue();
        MarketCapSnapshot snap = result.snapshot();
        assertThat(snap.maxSupply()).as("ETH has no max supply in CoinGecko").isNull();
        assertThat(snap.totalSupply()).isEqualByComparingTo(new BigDecimal("120100000.0000"));
    }

    @Test
    void mapMissingMarketCapReturnsUnavailable() {
        // market_cap = null → Phase B Universe 硬资格无值 → UNAVAILABLE
        CoinGeckoMarketData data = new CoinGeckoMarketData(
                "leaky", "leak",
                null, new BigDecimal("1000000"),
                new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("200"),
                null);
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("LEAK", data);

        assertThat(result.isUsable()).isFalse();
        assertThat(result.quality()).isEqualTo(CoinGeckoMapper.Quality.UNAVAILABLE);
        assertThat(result.reason()).contains("marketCap");
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void mapNegativeOrZeroMarketCapReturnsUnavailable() {
        // 真实 CoinGecko 偶发场景：mcap = 0（被除牌的币种）→ 视同无值
        CoinGeckoMarketData data = new CoinGeckoMarketData(
                "zero", "zero",
                BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null);
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("ZERO", data);

        assertThat(result.isUsable()).isFalse();
        assertThat(result.quality()).isEqualTo(CoinGeckoMapper.Quality.UNAVAILABLE);
        assertThat(result.snapshot()).isNull();
    }

    @Test
    void mapBlankBaseSymbolReturnsUnavailable() {
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("", CoinGeckoConstants.sampleBtc());
        assertThat(result.isUsable()).isFalse();
        assertThat(result.reason()).contains("blank");
    }

    @Test
    void mapNullDataReturnsUnavailable() {
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("BTC", null);
        assertThat(result.isUsable()).isFalse();
        assertThat(result.reason()).contains("no data");
    }

    @Test
    void mapReturnsAllFourCoreFieldsForFreshQuality() {
        // "all core fields present" = marketCap + circulating + (total OR max) → FRESH
        // 仅 marketCap + circulating → PARTIAL（max/total 缺失）
        CoinGeckoMarketData partial = new CoinGeckoMarketData(
                "x", "x", new BigDecimal("1000"), null,
                new BigDecimal("100"), null, null, null);
        CoinGeckoMapper.Result result = CoinGeckoMapper.map("X", partial);
        assertThat(result.isUsable()).isTrue();
        assertThat(result.quality()).isEqualTo(CoinGeckoMapper.Quality.PARTIAL);
        assertThat(result.snapshot().maxSupply()).isNull();
    }
}
