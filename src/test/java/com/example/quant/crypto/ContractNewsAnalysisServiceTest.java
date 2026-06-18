package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.news.NewsSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContractNewsAnalysisServiceTest {

    @Test
    void missingNewsDataUsesConfiguredLowScore() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.newsRisk().setMissingDataScore(18);
        ContractNewsAnalysisService service = new ContractNewsAnalysisService(
                new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) -> List.of()),
                agentProperties
        );

        ContractNewsRiskAnalysis analysis = service.analyze("PEPE-USDT-SWAP");

        assertThat(analysis.newsRiskLevel()).isEqualTo("UNKNOWN");
        assertThat(analysis.newsRiskScore()).isEqualTo(18);
        assertThat(analysis.decision().allowTrade()).isTrue();
        assertThat(analysis.decision().forceNoTrade()).isFalse();
    }

    @Test
    void unavailableNewsSourceUsesConfiguredLowScore() {
        AgentProperties agentProperties = new AgentProperties();
        agentProperties.newsRisk().setMissingDataScore(22);
        ContractNewsAnalysisService service = new ContractNewsAnalysisService(
                new NewsSearchService((marketType, symbol, name, keywords, maxResults, lookbackHours) -> {
                    throw new IllegalStateException("timeout");
                }),
                agentProperties
        );

        ContractNewsRiskAnalysis analysis = service.analyze("PEPE-USDT-SWAP");

        assertThat(analysis.newsRiskLevel()).isEqualTo("UNKNOWN");
        assertThat(analysis.newsRiskScore()).isEqualTo(22);
        assertThat(analysis.decision().allowTrade()).isTrue();
        assertThat(analysis.negativeEvents()).anyMatch(reason -> reason.contains("新闻数据源不可用"));
    }
}
