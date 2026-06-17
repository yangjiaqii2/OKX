package com.example.quant.okxtrade;

import com.example.quant.crypto.OkxRestClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OkxInstrumentRulesService implements OkxInstrumentRulesProvider {
    private static final Logger log = LoggerFactory.getLogger(OkxInstrumentRulesService.class);

    private final OkxRestClient okxRestClient;
    private final ConcurrentMap<String, OkxInstrumentRules> cache = new ConcurrentHashMap<>();

    public OkxInstrumentRulesService(OkxRestClient okxRestClient) {
        this.okxRestClient = okxRestClient;
    }

    @Override
    public OkxInstrumentRules rules(String instId) {
        if (instId == null || instId.isBlank()) {
            return OkxInstrumentRules.defaultFor(instId);
        }
        return cache.computeIfAbsent(instId, this::fetchRules);
    }

    private OkxInstrumentRules fetchRules(String instId) {
        try {
            JsonNode root = okxRestClient.publicGet("/api/v5/public/instruments?instType=SWAP&instId="
                    + OkxRestClient.encode(instId));
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode instrument = data.get(0);
                OkxInstrumentRules rules = new OkxInstrumentRules(
                        instId,
                        decimal(instrument, "ctVal"),
                        instrument.path("ctValCcy").asText(""),
                        decimal(instrument, "lotSz"),
                        decimal(instrument, "minSz"),
                        decimal(instrument, "tickSz")
                );
                log.info("Loaded OKX instrument rules instId={} ctVal={} ctValCcy={} lotSz={} minSz={} tickSz={}",
                        instId, rules.contractValue(), rules.contractValueCurrency(), rules.lotSize(),
                        rules.minSize(), rules.tickSize());
                return rules;
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to load OKX instrument rules instId={} message={}", instId, ex.getMessage());
        }
        return OkxInstrumentRules.defaultFor(instId);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = node.path(field).asText("0");
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}
