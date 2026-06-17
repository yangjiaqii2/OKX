package com.example.quant.crypto;

import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskDecision;
import com.example.quant.market.MarketType;
import com.example.quant.news.NewsSearchService;
import com.example.quant.news.dto.NewsItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class ContractNewsAnalysisService {
    private final NewsSearchService newsSearchService;

    public ContractNewsAnalysisService(NewsSearchService newsSearchService) {
        this.newsSearchService = newsSearchService;
    }

    public String status() {
        return "contract-news-analysis-ready";
    }

    public ContractNewsRiskAnalysis analyze(String instId) {
        try {
            String baseCurrency = baseCurrency(instId);
            List<NewsItem> items = newsSearchService.search(
                    MarketType.OKX_SWAP,
                    instId,
                    baseCurrency,
                    List.of(instId, baseCurrency),
                    10,
                    24
            );
            if (items == null || items.isEmpty()) {
                return ContractNewsRiskAnalysis.unknown("新闻数据源无返回，action最高只能WAIT_CONFIRM");
            }
            return classify(items);
        } catch (RuntimeException ex) {
            return ContractNewsRiskAnalysis.unknown("新闻数据源不可用：" + compact(ex.getMessage()));
        }
    }

    private ContractNewsRiskAnalysis classify(List<NewsItem> items) {
        int score = 90;
        List<String> eventTypes = new ArrayList<>();
        List<String> positives = new ArrayList<>();
        List<String> negatives = new ArrayList<>();
        boolean critical = false;
        boolean high = false;

        for (NewsItem item : items) {
            String text = normalized(item.title() + " " + item.contentSummary() + " " + item.riskKeywords());
            if (containsAny(text, "hack", "exploit", "rug", "vulnerability", "compromised", "stolen",
                    "bridge attack", "oracle attack", "暂停交易", "下架", "delist", "trading suspended")) {
                critical = true;
                score -= 80;
                eventTypes.add("SECURITY_OR_EXCHANGE_CRITICAL");
                negatives.add(item.title());
                continue;
            }
            if (containsAny(text, "暂停充值", "暂停提现", "withdrawal suspended", "deposit suspended",
                    "regulatory investigation", "sec lawsuit", "cftc", "项目方钱包", "foundation wallet")) {
                high = true;
                score -= 45;
                eventTypes.add("HIGH_RISK_EVENT");
                negatives.add(item.title());
                continue;
            }
            if (item.sentimentScore() <= -50 || item.importanceScore() >= 80 && item.sentimentScore() < 0) {
                high = true;
                score -= 25;
                eventTypes.add("NEGATIVE_NEWS");
                negatives.add(item.title());
            } else if (item.sentimentScore() >= 50 && item.importanceScore() >= 50) {
                score += 5;
                eventTypes.add("VERIFIED_POSITIVE");
                positives.add(item.title());
            }
        }

        score = Math.max(0, Math.min(100, score));
        if (critical) {
            return new ContractNewsRiskAnalysis(score, "CRITICAL", eventTypes, positives, negatives,
                    ContractNewsRiskDecision.noTrade("确认或疑似重大安全/交易所风险，直接NO_TRADE"));
        }
        if (high) {
            return new ContractNewsRiskAnalysis(Math.min(score, 55), "HIGH", eventTypes, positives, negatives,
                    ContractNewsRiskDecision.noTrade("存在可信负面新闻或事件风险，禁止自动交易"));
        }
        if (score < 80) {
            return new ContractNewsRiskAnalysis(score, "MEDIUM", eventTypes, positives, negatives,
                    ContractNewsRiskDecision.waitOnly("新闻或社交事件存在不确定性，降级WAIT_CONFIRM"));
        }
        return new ContractNewsRiskAnalysis(score, "LOW", eventTypes, positives, negatives,
                ContractNewsRiskDecision.allow("无重大负面新闻风险"));
    }

    private static String baseCurrency(String instId) {
        int index = instId == null ? -1 : instId.indexOf('-');
        return index > 0 ? instId.substring(0, index) : String.valueOf(instId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String compact(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 120 ? compact.substring(0, 120) + "..." : compact;
    }
}
