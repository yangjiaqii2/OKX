package com.example.quant.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EcbFxRateClientTest {
    @Test
    void calculatesUsdCnyFromEcbEuroReferenceRates() {
        Instant fetchedAt = Instant.parse("2026-08-12T16:05:00Z");
        String xml = """
                <Envelope><Cube><Cube time="2026-08-12">
                  <Cube currency="USD" rate="1.25"/>
                  <Cube currency="CNY" rate="8.00"/>
                </Cube></Cube></Envelope>
                """;

        EcbFxRateClient.Quote quote = EcbFxRateClient.parse(xml, fetchedAt);

        assertThat(quote.rate()).isEqualByComparingTo(new BigDecimal("6.4"));
        assertThat(quote.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    void rejectsIncompleteEcbResponse() {
        assertThatThrownBy(() -> EcbFxRateClient.parse(
                "<Envelope><Cube currency=\"USD\" rate=\"1.25\"/></Envelope>", Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ECB_RATE_MISSING");
    }

    @Test
    void parsesFrankfurterEcbFallback() {
        Instant fetchedAt = Instant.parse("2026-08-12T16:05:00Z");

        EcbFxRateClient.Quote quote = EcbFxRateClient.parseFrankfurter(
                "{\"amount\":1.0,\"base\":\"USD\",\"rates\":{\"CNY\":6.7432}}", fetchedAt);

        assertThat(quote.rate()).isEqualByComparingTo(new BigDecimal("6.7432"));
        assertThat(quote.source()).isEqualTo("FRANKFURTER_ECB");
    }
}
