package com.example.quant.agent.selection;

import com.example.quant.agent.gate.ContractTradeGate;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.filter.FilterContext;
import com.example.quant.crypto.filter.UniverseHardFilter;
import com.example.quant.crypto.provider.DataQuality;
import com.example.quant.crypto.provider.ProviderResult;
import com.example.quant.crypto.provider.dto.MarketCapSnapshot;
import com.example.quant.crypto.provider.dto.AthBreakoutSnapshot;
import com.example.quant.crypto.provider.dto.FundingHistorySnapshot;
import com.example.quant.crypto.provider.dto.OIDirection;
import com.example.quant.crypto.provider.dto.OpenInterestHistorySnapshot;
import com.example.quant.crypto.provider.dto.RelativeStrengthSnapshot;
import com.example.quant.crypto.selection.V2ScoreDimension;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiContractSelectionService {
    private static final Logger log = LoggerFactory.getLogger(AiContractSelectionService.class);
    private static final Set<String> ENTRY_MODES = Set.of("MARKET", "LIMIT", "WAIT_PULLBACK", "WAIT_RETEST");

    private final AiAnalysisService ai;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final UniverseHardFilter universeHardFilter;
    private final AtomicReference<ContractSelectionDecision> latest = new AtomicReference<>(ContractSelectionDecision.noEligible());

    /**
     * 5 参生产构造器：完整接入 Universe Hard Filter。
     * <p>这是 Phase B 的硬要求：即便上游（scanner / AutoTradeService）漏掉过滤，
     * eligiblePool 内部也会再次过 {@link UniverseHardFilter} +
     * {@link ContractTradeGate#aiHardDenyReasons}。Shadow 模式 / 单测直接调用本服务也不会绕过。
     */
    @Autowired
    public AiContractSelectionService(AiAnalysisService ai, ObjectMapper objectMapper, AgentProperties properties,
                                      UniverseHardFilter universeHardFilter) {
        this.ai = ai;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.universeHardFilter = universeHardFilter;
    }

    /**
     * 3 参兼容构造器：老测试 / 单独使用场景。用 noop filter，不会触发 V2 资格判断。
     * <p>{@link ContractTradeGate#aiHardDenyReasons} 仍会作为轻量兜底。
     */
    public AiContractSelectionService(AiAnalysisService ai, ObjectMapper objectMapper, AgentProperties properties) {
        this(ai, objectMapper, properties, UniverseHardFilter.noop());
    }

    public ContractSelectionDecision select(List<ContractCandidate> candidates) {
        return select(candidates, Map.of());
    }

    public ContractSelectionDecision select(List<ContractCandidate> candidates,
                                            Map<String, V2SelectionScoreResult> v2ScoreSnapshot) {
        Map<String, V2SelectionScoreResult> scores = v2ScoreSnapshot == null
                ? Map.of() : Map.copyOf(v2ScoreSnapshot);
        List<ContractCandidate> pool = eligiblePool(candidates, scores);
        log.info("AI selection started candidateCount={} eligibleCount={} enabled={} policy={}",
                candidates == null ? 0 : candidates.size(), pool.size(), properties.aiSelection().enabled(),
                properties.aiSelection().policy());
        if (pool.isEmpty()) {
            log.warn("AI selection skipped reason=NO_ELIGIBLE_CANDIDATE");
            return remember(ContractSelectionDecision.noEligible());
        }
        if (!properties.aiSelection().enabled()) {
            log.warn("AI selection skipped reason=AI_SELECTION_DISABLED eligibleCount={}", pool.size());
            return remember(ContractSelectionDecision.fallback("AI_SELECTION_DISABLED"));
        }
        try {
            ContractSelectionDecision decision = ai.isTypeSafeProvider()
                    ? parseTypeSafe(ai.completeTyped(typeSafeState(pool, scores), typeSafeQuestions(pool, scores)), pool)
                    : parse(ai.completeJson(systemPrompt(), userPrompt(pool, scores)), pool);
            log.info("AI selection completed source={} decision={} selectedInstId={} eligibleCount={}",
                    decision.source(), decision.decision(), decision.selectedInstId(), pool.size());
            return remember(decision);
        } catch (RuntimeException ex) {
            String reason = compact(ex.getMessage());
            log.warn("AI selection fallback reason={} eligibleCount={}", reason, pool.size());
            return remember(ContractSelectionDecision.fallback(reason));
        }
    }

    public ContractSelectionDecision latest() {
        return latest.get();
    }

    private ContractSelectionDecision remember(ContractSelectionDecision decision) {
        latest.set(decision);
        return decision;
    }

    private List<ContractCandidate> eligiblePool(List<ContractCandidate> candidates,
                                                 Map<String, V2SelectionScoreResult> scores) {
        if (candidates == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<ContractCandidate> filtered = new ArrayList<>();
        for (ContractCandidate candidate : candidates) {
            if (candidate == null || !hasText(candidate.instId()) || !seen.add(candidate.instId())) {
                continue;
            }
            if (!passesUniverseHardFilter(candidate)) {
                log.debug("AI eligible pool rejected by UniverseHardFilter instId={}", candidate.instId());
                continue;
            }
            List<String> aiDeny = ContractTradeGate.aiHardDenyReasons(candidate, properties);
            if (!aiDeny.isEmpty()) {
                log.debug("AI eligible pool rejected by aiHardDenyReasons instId={} reasons={}",
                        candidate.instId(), aiDeny);
                continue;
            }
            filtered.add(candidate);
        }
        filtered.sort((left, right) -> V2SelectionScoreCalculator.compareForRanking(left, right, scores));
        if (filtered.size() > properties.aiSelection().candidateLimit()) {
            return List.copyOf(filtered.subList(0, properties.aiSelection().candidateLimit()));
        }
        return List.copyOf(filtered);
    }

    private boolean passesUniverseHardFilter(ContractCandidate c) {
        try {
            // 用 candidate 自身的 V2 字段构造 ProviderResult（fresh 来源 = AI_POOL_DERIVED）。
            // 任何 null 字段会自然被对应 Filter 判定为 MISSING / UNAVAILABLE → 拒绝。
            // 这是 Universe Hard Filter 作为安全网的职责：上游漏字段，这里必须挡住。
            MarketCapSnapshot snapshot = new MarketCapSnapshot(
                    c.baseCurrency(),
                    c.marketCapUsd(),
                    c.fdvUsd(),
                    null,
                    null,
                    null,
                    java.time.Instant.now(),
                    "AI_POOL_DERIVED"
            );
            DataQuality quality = (c.marketCapUsd() != null) ? DataQuality.FRESH : DataQuality.UNAVAILABLE;
            ProviderResult<MarketCapSnapshot> mcapResult = new ProviderResult<>(
                    snapshot, quality, java.time.Instant.now(), "AI_POOL_DERIVED", null, null);
            // Phase B V2：把 candidate 自身的 V2 ratio 字段（circulationRatio / volumeMcapRatio）
            // 传给 CirculationFilter / VolumeMcapFilter，让 ratio 优先路径生效，
            // 避免 NullMarketCapProvider 没填 snapshot absolute 时 filter 误杀一切。
            FilterContext ctx = FilterContext.withV2Ratios(
                    c.instId(),
                    c.baseCurrency(),
                    c.lastPrice(),
                    c.volume24h(),
                    null,
                    c.spreadBps(),
                    c.bidDepthUsdt(),
                    c.openInterest(),
                    mcapResult,
                    c.circulationRatio(),
                    c.volumeMcapRatio()
            );
            UniverseHardFilter.SingleOutcome outcome = universeHardFilter.apply(ctx);
            return outcome.passed();
        } catch (RuntimeException ex) {
            log.warn("UniverseHardFilter threw on instId={} message={}", c.instId(), ex.getMessage());
            return false;
        }
    }

    private ContractSelectionDecision parse(JsonNode root, List<ContractCandidate> pool) {
        String actionText = required(root, "decision").toUpperCase(Locale.ROOT);
        ContractSelectionDecision.SelectionAction action = enumValue(
                ContractSelectionDecision.SelectionAction.class, actionText, "decision");
        BigDecimal confidence = decimal(root, "confidence");
        requireRange(confidence, "confidence");
        List<String> reasons = strings(root.path("reasonList"));
        List<String> risks = strings(root.path("riskList"));
        if (action == ContractSelectionDecision.SelectionAction.WAIT
                && !hasText(optional(root, "selectedInstId", ""))) {
            return new ContractSelectionDecision(
                    ContractSelectionDecision.DecisionSource.AI_WAIT, action, "", null,
                    optional(root, "entryMode", "WAIT"), confidence, List.of(), reasons, risks, "");
        }

        Set<String> allowed = pool.stream().map(ContractCandidate::instId).collect(java.util.stream.Collectors.toSet());
        String selectedInstId = required(root, "selectedInstId");
        if (!allowed.contains(selectedInstId)) {
            throw new IllegalArgumentException("selectedInstId is outside eligible pool: " + selectedInstId);
        }
        DirectionBias direction = direction(required(root, "direction"));
        String entryMode = entryMode(required(root, "entryMode"));
        List<ContractSelectionDecision.RankedCandidate> ranked = ranked(root.path("rankedCandidates"), allowed);
        if (ranked.isEmpty() || !selectedInstId.equals(ranked.get(0).instId())) {
            throw new IllegalArgumentException("rankedCandidates first item must match selectedInstId");
        }
        if (direction != ranked.get(0).direction()) {
            throw new IllegalArgumentException("top-level direction conflicts with rankedCandidates first direction");
        }
        for (ContractSelectionDecision.RankedCandidate item : ranked) {
            DirectionBias candidateDirection = pool.stream()
                    .filter(candidate -> item.instId().equals(candidate.instId()))
                    .map(ContractCandidate::trendDirection)
                    .findFirst()
                    .orElse(null);
            if (candidateDirection != item.direction()) {
                throw new IllegalArgumentException("AI direction conflicts with deterministic candidate direction: "
                        + item.instId());
            }
        }
        boolean waitingEntry = entryMode.startsWith("WAIT");
        if (action == ContractSelectionDecision.SelectionAction.WAIT && !waitingEntry) {
            throw new IllegalArgumentException("WAIT decision requires WAIT entryMode");
        }
        ContractSelectionDecision.SelectionAction normalizedAction = waitingEntry
                ? ContractSelectionDecision.SelectionAction.WAIT : action;
        ContractSelectionDecision.DecisionSource source = waitingEntry
                ? ContractSelectionDecision.DecisionSource.AI_WAIT
                : ContractSelectionDecision.DecisionSource.AI_PRIMARY;
        return new ContractSelectionDecision(
                source, normalizedAction, selectedInstId, direction,
                entryMode, confidence, ranked, reasons, risks, "");
    }

    private List<ContractSelectionDecision.RankedCandidate> ranked(JsonNode node, Set<String> allowed) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("rankedCandidates must be an array");
        }
        List<ContractSelectionDecision.RankedCandidate> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            String instId = required(item, "instId");
            int rank = item.path("rank").asInt(-1);
            if (!allowed.contains(instId) || !seen.add(instId) || rank != result.size() + 1) {
                throw new IllegalArgumentException("invalid rankedCandidates item: " + instId);
            }
            BigDecimal confidence = decimal(item, "confidence");
            requireRange(confidence, "rankedCandidates.confidence");
            result.add(new ContractSelectionDecision.RankedCandidate(
                    instId, rank, direction(required(item, "direction")),
                    entryMode(required(item, "entryMode")), confidence,
                    strings(item.path("reasonList")), strings(item.path("riskList"))));
        }
        return List.copyOf(result);
    }

    private String userPrompt(List<ContractCandidate> pool, Map<String, V2SelectionScoreResult> scores) {
        try {
            List<Map<String, Object>> payload = candidatePayload(pool, scores);
            return "从以下 Java 已经筛选出的 eligible candidates 中选择或 WAIT。softRule* 仅是规则层参考；"
                    + "只能返回池内 instId；不得绕过 hard deny，也不得反转 deterministic candidate direction。候选="
                    + objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("AI candidate serialization failed", ex);
        }
    }

    private Map<String, Object> typeSafeState(List<ContractCandidate> pool,
                                              Map<String, V2SelectionScoreResult> scores) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("task", "在 Java 已筛选的 eligible candidates 中选择或 WAIT。只能确认候选的 deterministic direction，"
                + "不得反转方向或绕过 hard deny；缺失证据不是中性证据，低 coverage 应降低确定性。");
        state.put("candidates", candidatePayload(pool, scores));
        return state;
    }

    private Map<String, Object> typeSafeQuestions(List<ContractCandidate> pool,
                                                  Map<String, V2SelectionScoreResult> scores) {
        Map<String, Object> questions = new LinkedHashMap<>();
        questions.put("decision", Map.of(
                "type", "choice",
                "instructions", "Should the system trade one of these already eligible candidates now?",
                "criteria", Map.of(
                        "TRADE", "A clear opportunity exists and execution is appropriate now.",
                        "WAIT", "Do not enter now; wait for a better setup or confirmation.")));
        Map<String, String> selectedCriteria = new LinkedHashMap<>();
        for (ContractCandidate candidate : pool) {
            V2SelectionScoreResult score = scores.get(candidate.instId());
            selectedCriteria.put(candidate.instId(), "Eligible candidate " + candidate.instId()
                    + ", v2RankingScore=" + (score == null ? null : score.rankingScore())
                    + ", v2CoverageRatio=" + (score == null ? null : score.coverageRatio())
                    + ", trend=" + candidate.trendDirection());
        }
        questions.put("selectedInstId", Map.of(
                "type", "choice",
                "instructions", "Which eligible contract is the best candidate?",
                "criteria", selectedCriteria));
        questions.put("direction", Map.of(
                "type", "choice",
                "instructions", "Confirm the selected candidate's deterministic direction; do not reverse it.",
                "criteria", Map.of(
                        "LONG", "Long / bullish direction.",
                        "SHORT", "Short / bearish direction.")));
        questions.put("entryMode", Map.of(
                "type", "choice",
                "instructions", "What entry mode best fits the selected setup?",
                "criteria", Map.of(
                        "MARKET", "Enter at market when conditions are ready.",
                        "LIMIT", "Use a limit entry when price improvement is appropriate.",
                        "WAIT_PULLBACK", "Wait for a pullback before entering.",
                        "WAIT_RETEST", "Wait for a retest or confirmation before entering.")));
        questions.put("quality", Map.of(
                "type", "score",
                "instructions", "How strong is the selected setup for a disciplined trade?",
                "criteria", List.of(
                        "Insufficient or conflicting; do not trade.",
                        "Weak; wait for improvement.",
                        "Mixed but potentially workable with confirmation.",
                        "Good; suitable if deterministic risk checks remain clear.",
                        "Strong; clear setup with favorable execution conditions.")));
        questions.put("risk_ok", Map.of(
                "type", "noul",
                "instructions", "Does the selected setup appear suitable to trade without ignoring market, liquidity, news, or direction risk?"));
        return questions;
    }

    private ContractSelectionDecision parseTypeSafe(JsonNode root, List<ContractCandidate> pool) {
        JsonNode answers = root.path("answers");
        String decisionText = typedChoice(root, answers, "decision").toUpperCase(Locale.ROOT);
        String selectedInstId = typedChoice(root, answers, "selectedInstId");
        String directionText = typedChoice(root, answers, "direction").toUpperCase(Locale.ROOT);
        String entryModeText = typedChoice(root, answers, "entryMode").toUpperCase(Locale.ROOT);
        BigDecimal confidence = typedConfidence(root, answers, "decision");
        confidence = confidence.min(typedConfidence(root, answers, "selectedInstId"));
        confidence = confidence.min(typedConfidence(root, answers, "direction"));
        confidence = confidence.min(typedConfidence(root, answers, "entryMode"));
        confidence = confidence.min(typedConfidence(root, answers, "quality", "scores"));
        double quality = typedScore(root, answers, "quality");
        double riskAcceptableProbability = typedNoul(root, answers, "risk_ok");
        if ("TRADE".equals(decisionText) && (quality < 2.0 || riskAcceptableProbability < 0.65)) {
            decisionText = "WAIT";
            entryModeText = "WAIT_RETEST";
        }
        if ("WAIT".equals(decisionText) && !entryModeText.startsWith("WAIT")) {
            entryModeText = "WAIT_PULLBACK";
        }
        if (!"TRADE".equals(decisionText) && !"WAIT".equals(decisionText)) {
            throw new IllegalArgumentException("TypeSafe decision must be TRADE or WAIT");
        }
        if (pool.stream().noneMatch(candidate -> candidate.instId().equals(selectedInstId))) {
            throw new IllegalArgumentException("TypeSafe selectedInstId is outside eligible pool: " + selectedInstId);
        }
        ObjectNode normalized = objectMapper.createObjectNode();
        normalized.put("decision", decisionText);
        normalized.put("selectedInstId", selectedInstId);
        normalized.put("direction", directionText);
        normalized.put("entryMode", entryModeText);
        normalized.put("confidence", confidence.doubleValue());
        normalized.putArray("reasonList").add("TypeSafe Jev typed decision");
        normalized.putArray("riskList").add("TypeSafe risk acceptable probability=" + riskAcceptableProbability);
        normalized.putArray("rankedCandidates").addObject()
                .put("instId", selectedInstId)
                .put("rank", 1)
                .put("direction", directionText)
                .put("entryMode", entryModeText)
                .put("confidence", confidence.doubleValue());
        return parse(normalized, pool);
    }

    private List<Map<String, Object>> candidatePayload(
            List<ContractCandidate> pool,
            Map<String, V2SelectionScoreResult> scores
    ) {
        return pool.stream().map(candidate -> {
            V2SelectionScoreResult v2 = scores.get(candidate.instId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("instId", candidate.instId());
            item.put("lastPrice", candidate.lastPrice());
            item.put("changePercent24h", candidate.changePercent24h());
            item.put("volume24hUsdt", candidate.volume24h());
            item.put("fundingRate", candidate.fundingRate());
            item.put("openInterest", candidate.openInterest());
            item.put("legacyOpenInterestChange", candidate.openInterestChange());
            item.put("trendDirection", candidate.trendDirection());
            item.put("softRuleSignal", candidate.signalType());
            item.put("softRuleScore", candidate.finalRankScore());
            item.put("spreadBps", candidate.spreadBps());
            item.put("estimatedSlippageBps", candidate.estimatedSlippageBps());
            item.put("marketRiskLevel", candidate.marketRiskLevel());
            item.put("newsRisk", candidate.newsAnalysis() == null ? null : candidate.newsAnalysis().newsRiskLevel());
            item.put("softRuleReasons", candidate.candidateReasonList());
            item.put("softRuleDiagnostics", candidate.riskTagList());
            item.put("v2RankingScore", v2 == null ? null : v2.rankingScore());
            item.put("v2NormalizedScore", v2 == null ? null : v2.normalizedScore());
            item.put("v2CoverageRatio", v2 == null ? null : v2.coverageRatio());
            item.put("v2AvailableWeight", v2 == null ? null : v2.availableWeight());
            item.put("capitalFlowScore", dimensionScore(v2, V2ScoreDimension.CAPITAL_FLOW));
            item.put("derivativesScore", dimensionScore(v2, V2ScoreDimension.DERIVATIVES));
            item.put("relativeStrengthScore", dimensionScore(v2, V2ScoreDimension.RELATIVE_STRENGTH));
            item.put("marketStructureScore", dimensionScore(v2, V2ScoreDimension.MARKET_STRUCTURE));
            item.put("volumeQualityScore", dimensionScore(v2, V2ScoreDimension.VOLUME_QUALITY));
            item.put("breakoutScore", dimensionScore(v2, V2ScoreDimension.BREAKOUT));
            item.put("newsScore", dimensionScore(v2, V2ScoreDimension.NEWS));

            OpenInterestHistorySnapshot oi = candidate.openInterestHistory();
            item.put("oiHistoryAvailable", oi != null);
            item.put("oiChange1h", oi == null ? null : oi.oiChange1h());
            item.put("oiChange4h", oi == null ? null : oi.oiChange4h());
            item.put("oiChange24h", oi == null ? null : oi.oiChange24h());
            item.put("oiDirection", oi == null || oi.oiChange24h() == null ? null
                    : OIDirection.classify(candidate.changePercent24h(), oi.oiChange24h()).name());

            RelativeStrengthSnapshot rs = candidate.relativeStrength();
            item.put("rs1h", rs == null ? null : rs.rs1h());
            item.put("rs4h", rs == null ? null : rs.rs4h());
            item.put("rs24h", rs == null ? null : rs.rs24h());
            item.put("relativeStrengthDataQuality", rs == null || rs.dataQuality() == null
                    ? "UNAVAILABLE" : rs.dataQuality().name());

            FundingHistorySnapshot funding = candidate.fundingHistory();
            item.put("fundingHistoryCurrentRate", funding == null ? null : funding.currentRate());
            item.put("fundingRate24h", funding == null ? null : funding.rate24h());
            item.put("fundingSlope", funding == null ? null : funding.slope());
            item.put("fundingTrend", funding == null || funding.trend() == null ? null : funding.trend().name());
            item.put("fundingExtreme", funding == null ? null : funding.extreme());
            item.put("fundingRawRates", funding == null ? null : funding.rawRates());
            item.put("fundingDataQuality", funding == null || funding.dataQuality() == null
                    ? "UNAVAILABLE" : funding.dataQuality().name());

            AthBreakoutSnapshot ath = candidate.athBreakout();
            item.put("breakoutApplicable", candidate.trendDirection() != DirectionBias.BEARISH);
            item.put("athPrice", ath == null ? null : ath.athPrice());
            item.put("distanceFromAth", ath == null ? null : ath.distanceToAthPct());
            item.put("breakoutLevel", ath == null ? null : ath.breakoutLevel());
            item.put("breakoutDataQuality", ath == null || ath.dataQuality() == null
                    ? "UNAVAILABLE" : ath.dataQuality().name());
            return item;
        }).toList();
    }

    private static BigDecimal dimensionScore(V2SelectionScoreResult result, V2ScoreDimension dimension) {
        return result == null || result.dimensionScores() == null
                ? null : result.dimensionScores().get(dimension);
    }

    private static String typedChoice(JsonNode root, JsonNode answers, String name) {
        JsonNode answer = typedAnswer(root, answers, name, "choices");
        String value = answer.path("choice").asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("TypeSafe answer missing choice: " + name);
        }
        return value;
    }

    private static BigDecimal typedConfidence(JsonNode root, JsonNode answers, String name) {
        return typedConfidence(root, answers, name, "choices");
    }

    private static BigDecimal typedConfidence(JsonNode root, JsonNode answers, String name, String groupedKey) {
        JsonNode answer = typedAnswer(root, answers, name, groupedKey);
        BigDecimal confidence = answer.path("confidence").isNumber()
                ? answer.path("confidence").decimalValue() : BigDecimal.ZERO;
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("TypeSafe confidence out of range: " + name);
        }
        return confidence;
    }

    private static double typedScore(JsonNode root, JsonNode answers, String name) {
        JsonNode answer = typedAnswer(root, answers, name, "scores");
        if (!answer.path("score").isNumber()) {
            throw new IllegalArgumentException("TypeSafe answer missing score: " + name);
        }
        return answer.path("score").asDouble();
    }

    private static double typedNoul(JsonNode root, JsonNode answers, String name) {
        JsonNode answer = typedAnswer(root, answers, name, "nouls");
        if (!answer.path("noul").isNumber()) {
            throw new IllegalArgumentException("TypeSafe answer missing noul: " + name);
        }
        double value = answer.path("noul").asDouble();
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException("TypeSafe noul out of range: " + name);
        }
        return value;
    }

    private static JsonNode typedAnswer(JsonNode root, JsonNode answers, String name, String groupedKey) {
        JsonNode answer = answers.path(name);
        if (answer.isMissingNode()) {
            answer = root.path(groupedKey).path(name);
        }
        if (answer.isMissingNode() || !answer.isObject()) {
            throw new IllegalArgumentException("TypeSafe answer missing: " + name);
        }
        return answer;
    }

    private static String systemPrompt() {
        return "你是合约交易选择器。你的任务是在 Java 已经筛选出的 eligible candidates 中最终选择或 WAIT。"
                + "进入候选池代表当前不可绕过的硬风险已通过；softRule* 是辅助诊断，软规则不是硬否决。"
                + "不得发明池外候选、不得绕过 hard deny、不得反转 deterministic candidate direction。"
                + "候选 BULLISH 只能 LONG 或 WAIT，候选 BEARISH 只能 SHORT 或 WAIT。"
                + "数据缺失不能被解释为中性证据；coverageRatio 和 availableWeight 较低时应降低确定性，必要时 WAIT。"
                + "你应基于价格、趋势、量能、OI、Funding、流动性和风险收益独立决定 TRADE 或 WAIT，"
                + "趋势方向明确、流动性和执行成本良好且未明显过热时，优先选择 MARKET 或 LIMIT；"
                + "不要求所有周期完全一致，但核心方向不能相互冲突。"
                + "WAIT_PULLBACK / WAIT_RETEST 只用于短线明显过热、结构尚未确认、价格明显偏离或风险收益不合适。"
                + "不得为了增加交易次数忽略市场风险、新闻风险、流动性或方向冲突，也不要把单一软规则标签当成否决。"
                + "严格返回 JSON：decision=TRADE|WAIT, selectedInstId, direction=LONG|SHORT, "
                + "entryMode=MARKET|LIMIT|WAIT_PULLBACK|WAIT_RETEST, confidence=0..1, "
                + "rankedCandidates=[{instId,rank,direction,entryMode,confidence}], reasonList, riskList。"
                + "TRADE 只能搭配 MARKET/LIMIT；WAIT 只能搭配 WAIT_PULLBACK/WAIT_RETEST。"
                + "选择等待机会时也必须给出池内 selectedInstId、direction 和 rankedCandidates；"
                + "selectedInstId 必须是 rankedCandidates 第一项；不要输出额外文字。";
    }

    private static DirectionBias direction(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "LONG" -> DirectionBias.BULLISH;
            case "SHORT" -> DirectionBias.BEARISH;
            default -> throw new IllegalArgumentException("direction must be LONG or SHORT");
        };
    }

    private static String entryMode(String value) {
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!ENTRY_MODES.contains(normalized)) {
            throw new IllegalArgumentException("invalid entryMode: " + value);
        }
        return normalized;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optional(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try {
            return new BigDecimal(required(node, field));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be numeric", ex);
        }
    }

    private static void requireRange(BigDecimal value, String field) {
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = compact(item.asText(""));
            if (!value.isEmpty() && values.size() < 8) {
                values.add(value);
            }
        });
        return List.copyOf(values);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid " + field + ": " + value, ex);
        }
    }

    private static String compact(String value) {
        if (value == null) {
            return "AI_SELECTION_FAILED";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.substring(0, Math.min(200, compact.length()));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
