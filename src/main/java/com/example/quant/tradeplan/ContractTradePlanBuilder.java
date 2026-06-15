package com.example.quant.tradeplan;

import com.example.quant.ai.AiAnalysisService;
import com.example.quant.crypto.OkxContractScanner;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.market.DirectionBias;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ContractTradePlanBuilder {
    private final OkxContractScanner scanner;
    private final AiAnalysisService aiAnalysisService;

    public ContractTradePlanBuilder(OkxContractScanner scanner, AiAnalysisService aiAnalysisService) {
        this.scanner = scanner;
        this.aiAnalysisService = aiAnalysisService;
    }

    public TradePlan buildPlan(String instId) {
        ContractCandidate candidate = scanner.scan().stream()
                .filter(item -> item.instId().equals(instId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("OKX did not return contract: " + instId));
        JsonNode plan = aiAnalysisService.completeJson(systemPrompt(), userPrompt(candidate));
        TradePlanType action = action(plan.path("action").asText(""));
        if (action == null) {
            throw new IllegalStateException("AI建议暂不生成交易计划：" + plan.path("invalidCondition").asText("条件不足"));
        }

        BigDecimal entry = positiveDecimal(plan, "entryPrice", candidate.lastPrice());
        BigDecimal stopLoss = positiveDecimal(plan, "stopLossPrice", candidate.stopLossPrice());
        BigDecimal takeProfit = positiveDecimal(plan, "takeProfitPrice", candidate.takeProfitPrice());
        int leverage = analyzedLeverage(candidate, plan.path("leverage").asInt(candidate.suggestedLeverage()));
        BigDecimal confidence = decimal(plan, "confidence", BigDecimal.valueOf(0.5)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal riskRewardRatio = riskReward(action, entry, stopLoss, takeProfit);
        DirectionBias direction = action == TradePlanType.OPEN_SHORT ? DirectionBias.BEARISH : DirectionBias.BULLISH;

        return new TradePlan(
                UUID.randomUUID(),
                instId,
                action,
                plan.path("orderType").asText("LIMIT"),
                direction,
                confidence,
                entry,
                stopLoss,
                takeProfit,
                leverage,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                riskRewardRatio,
                candidate.score(),
                candidate.fundingRate(),
                candidate.volatility(),
                candidate.volume24h(),
                "cross",
                stringList(plan.path("reasonList"), "AI生成的交易计划"),
                stringList(plan.path("riskList"), "市场波动风险"),
                plan.path("invalidCondition").asText("价格结构或风控条件失效"),
                true,
                Instant.now().plusSeconds(300)
        );
    }

    private static String systemPrompt() {
        return """
                你是一个只做OKX USDT永续合约的交易计划分析器。
                必须输出严格JSON，不要Markdown，不要解释。
                你只能生成待人工确认的计划，不能要求直接下单。
                如果条件不足，action输出WAIT。
                杠杆leverage必须结合波动率、资金费率、ATR止损距离和清算风险，不允许固定倍数。
                leverage不得超过输入里的系统建议杠杆。
                输出字段：
                action: OPEN_LONG | OPEN_SHORT | WAIT
                orderType: LIMIT | MARKET
                entryPrice: number
                stopLossPrice: number
                takeProfitPrice: number
                leverage: number
                confidence: 0到1
                reasonList: string[]
                riskList: string[]
                invalidCondition: string
                """;
    }

    private static String userPrompt(ContractCandidate candidate) {
        return """
                基于以下OKX合约实时数据生成一个保守交易计划。只允许在风险收益比合理时输出OPEN_LONG或OPEN_SHORT。
                合约: %s
                最新价: %s
                24h涨跌幅百分比: %s
                24h成交量USDT: %s
                趋势方向: %s
                综合评分: %s
                系统建议杠杆上限: %sx
                系统分析止损价: %s
                系统分析止盈价: %s
                系统分析风险收益比: %s
                5m涨跌幅百分比: %s
                量能放大倍数: %s
                资金费率: %s
                持仓量: %s
                ATR波动率: %s
                入选理由: %s
                风险标签: %s
                """.formatted(
                candidate.instId(),
                candidate.lastPrice(),
                candidate.changePercent24h(),
                candidate.volume24h(),
                candidate.trendDirection(),
                candidate.score(),
                candidate.suggestedLeverage(),
                candidate.stopLossPrice(),
                candidate.takeProfitPrice(),
                candidate.riskRewardRatio(),
                candidate.changePercent5m(),
                candidate.volumeSpikeRatio(),
                candidate.fundingRate(),
                candidate.openInterest(),
                candidate.volatility(),
                candidate.candidateReasonList(),
                candidate.riskTagList()
        );
    }

    private static TradePlanType action(String value) {
        return switch (value) {
            case "OPEN_LONG" -> TradePlanType.OPEN_LONG;
            case "OPEN_SHORT" -> TradePlanType.OPEN_SHORT;
            default -> null;
        };
    }

    private static BigDecimal decimal(JsonNode node, String field, BigDecimal fallback) {
        String value = node.path(field).asText("");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field, BigDecimal fallback) {
        BigDecimal value = decimal(node, field, fallback);
        if (value == null || value.signum() <= 0) {
            throw new IllegalStateException("AI计划字段无效：" + field);
        }
        return value;
    }

    private static BigDecimal riskReward(TradePlanType action, BigDecimal entry, BigDecimal stopLoss, BigDecimal takeProfit) {
        BigDecimal risk;
        BigDecimal reward;
        if (action == TradePlanType.OPEN_SHORT) {
            risk = stopLoss.subtract(entry);
            reward = entry.subtract(takeProfit);
        } else {
            risk = entry.subtract(stopLoss);
            reward = takeProfit.subtract(entry);
        }
        if (risk.signum() <= 0 || reward.signum() <= 0) {
            throw new IllegalStateException("AI计划止盈止损方向不合理");
        }
        return reward.divide(risk, 2, RoundingMode.HALF_UP);
    }

    private static int analyzedLeverage(ContractCandidate candidate, int aiLeverage) {
        int analyzed = Math.max(1, candidate.suggestedLeverage());
        int requested = Math.max(1, aiLeverage);
        return Math.min(analyzed, requested);
    }

    private static List<String> stringList(JsonNode node, String fallback) {
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item.asText("");
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return result.isEmpty() ? List.of(fallback) : result;
    }
}
