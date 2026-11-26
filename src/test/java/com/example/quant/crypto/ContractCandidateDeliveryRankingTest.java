package com.example.quant.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.crypto.selection.V2SelectionScoreCalculator;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 交付候选的排序职责。
 *
 * <p>深度分析产出的候选数量（deepAnalysisLimit，默认 30）远大于交付上限
 * （topCandidateLimit，默认 10）。本测试锁住：<b>截断发生前必须先做交易资格判断</b>，
 * 否则"仅可观察、当前不可交易"的候选会把合法交易候选挤出交付列表，
 * 而下游 {@code AutoTradeService} 只能看到交付列表。
 *
 * <p>入围依据 = 交易资格 + V2 rankingScore；排序依据 = 同一把尺子；
 * 硬门控仍然留在下游，本层不做任何放行。
 */
class ContractCandidateDeliveryRankingTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("前十展示候选不可交易时，排名靠后的合法交易候选仍能进入交付列表")
    void tradeEligibleCandidateOutsideDisplayHeadStillEntersDeliveredList() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        List<ContractCandidate> candidates = new ArrayList<>();
        // 前 10 名 V2 分数最高，但没有一个能通过规则资格（signalType 不可交易）。
        for (int i = 0; i < 10; i++) {
            candidates.add(ineligibleCandidate("SHOWCASE" + i + "-USDT-SWAP"));
        }
        // 第 11 个 V2 分数最低，但完全满足规则资格。
        ContractCandidate eligible = eligibleCandidate("TRADABLE-USDT-SWAP");
        candidates.add(eligible);

        Map<String, V2SelectionScoreResult> scores = scoresRankingEligibleLast(candidates, eligible.instId());

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(candidates, scores, properties);

        assertThat(delivered).hasSize(10);
        assertThat(delivered).extracting(ContractCandidate::instId)
                .as("合法交易候选必须进入交付列表，否则下游永远看不到它")
                .contains("TRADABLE-USDT-SWAP");
        assertThat(delivered.get(0).instId())
                .as("交易资格优先于纯观察排名")
                .isEqualTo("TRADABLE-USDT-SWAP");
    }

    @Test
    @DisplayName("交付数量仍然受 topCandidateLimit 限制，不因资格优先而扩容")
    void deliveredListStillRespectsTopCandidateLimit() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        List<ContractCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            candidates.add(eligibleCandidate("OK" + i + "-USDT-SWAP"));
        }

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                candidates, uniformScores(candidates), properties);

        assertThat(delivered).hasSize(10);
    }

    @Test
    @DisplayName("资格相同的候选之间仍按 V2 rankingScore 排序")
    void equallyEligibleCandidatesKeepV2RankingOrder() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        List<ContractCandidate> candidates = List.of(
                eligibleCandidate("LOW-USDT-SWAP"),
                eligibleCandidate("HIGH-USDT-SWAP"),
                eligibleCandidate("MID-USDT-SWAP"));

        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        scores.put("LOW-USDT-SWAP", score("10"));
        scores.put("HIGH-USDT-SWAP", score("90"));
        scores.put("MID-USDT-SWAP", score("50"));

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(candidates, scores, properties);

        assertThat(delivered).extracting(ContractCandidate::instId)
                .containsExactly("HIGH-USDT-SWAP", "MID-USDT-SWAP", "LOW-USDT-SWAP");
    }

    @Test
    void tiedRankingScoreFavorsCompleteEvidenceInDeliveryAndExecution() {
        ContractCandidate complete = eligibleCandidate("FULL-USDT-SWAP");
        ContractCandidate incomplete = eligibleCandidate("PARTIAL-USDT-SWAP");
        var scores = Map.of(
                complete.instId(), score("63"),
                incomplete.instId(), new V2SelectionScoreResult(
                        new BigDecimal("70"), new BigDecimal("63"), new BigDecimal("90"),
                        new BigDecimal("0.90"), Map.of(), List.of()));
        List<ContractCandidate> candidates = List.of(incomplete, complete);

        assertThat(OkxContractScanner.rankForDelivery(candidates, scores, new AgentProperties()))
                .containsExactly(complete, incomplete);
        assertThat(V2SelectionScoreCalculator.compareForRanking(complete, incomplete, scores)).isNegative();
    }

    @Test
    @DisplayName("资格优先不放松任何硬门控：不可交易候选仍被标记为不可交易")
    void eligibilityPriorityDoesNotRelaxAnyGate() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        ContractCandidate ineligible = ineligibleCandidate("BLOCKED-USDT-SWAP");
        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                List.of(ineligible), uniformScores(List.of(ineligible)), properties);

        assertThat(delivered).hasSize(1);
        assertThat(com.example.quant.agent.gate.ContractTradeGate.planDenyReasons(delivered.get(0), properties))
                .as("进入交付列表不等于通过风控")
                .isNotEmpty();
        assertThat(com.example.quant.agent.gate.ContractTradeGate.autoDenyReasons(delivered.get(0), properties))
                .isNotEmpty();
    }

    @Test
    @DisplayName("排序确定性：相同输入重复排序结果完全一致")
    void deliveryRankingIsDeterministic() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        List<ContractCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(eligibleCandidate("SAME" + i + "-USDT-SWAP"));
        }
        Map<String, V2SelectionScoreResult> scores = uniformScores(candidates);

        List<String> first = OkxContractScanner.rankForDelivery(candidates, scores, properties)
                .stream().map(ContractCandidate::instId).toList();
        List<String> second = OkxContractScanner.rankForDelivery(candidates, scores, properties)
                .stream().map(ContractCandidate::instId).toList();

        assertThat(second).containsExactlyElementsOf(first);
        assertThat(first).isSorted();
    }

    // ------------------------------------------------------------------
    @Test
    void noTradeCannotDisplaceWaitingCandidateWithLowerScore() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(1);
        var blocked = candidate("BLOCKED-USDT-SWAP", ContractSignalType.STRONG_LONG, "NO_TRADE",
                95, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80), new BigDecimal("2.5"));
        var waiting = planEligibleButWaitingCandidate("WAIT-USDT-SWAP");
        var scores = Map.of(blocked.instId(), score("99"), waiting.instId(), score("50"));
        assertThat(OkxContractScanner.rankForDelivery(List.of(blocked, waiting), scores, properties))
                .containsExactly(waiting);
    }

    // 三类候选必须真正分开：立即可交易 / 计划合格但动作未就绪 / 不可交易
    // ------------------------------------------------------------------

    @Test
    @DisplayName("计划条件合格但动作仍为 WAIT 的候选，排在立即可交易候选之后")
    void planEligibleButActionNotReadyRanksBelowImmediatelyTradableCandidate() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        // 两者都能通过 planDenyReasons / aiHardDenyReasons，差别只在 action。
        ContractCandidate waitAction = planEligibleButWaitingCandidate("WAITACT-USDT-SWAP");
        ContractCandidate tradable = eligibleCandidate("TRADABLE-USDT-SWAP");

        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        scores.put("WAITACT-USDT-SWAP", score("99"));
        scores.put("TRADABLE-USDT-SWAP", score("10"));

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                List.of(waitAction, tradable), scores, properties);

        assertThat(com.example.quant.agent.gate.ContractTradeGate.planDenyReasons(waitAction, properties))
                .as("前提：该候选确实通过了计划门控")
                .isEmpty();
        assertThat(com.example.quant.agent.gate.ContractTradeGate.autoDenyReasons(waitAction, properties))
                .as("前提：该候选的动作条件确实未就绪")
                .isNotEmpty();
        assertThat(delivered).extracting(ContractCandidate::instId)
                .as("立即可交易优先于计划合格但仍需等待触发的候选")
                .containsExactly("TRADABLE-USDT-SWAP", "WAITACT-USDT-SWAP");
    }

    @Test
    @DisplayName("计划合格但待触发的候选仍排在完全不可交易的候选之前，等待生命周期不被牺牲")
    void planEligibleButWaitingStillOutranksFullyIneligibleCandidate() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        ContractCandidate waitAction = planEligibleButWaitingCandidate("WAITACT-USDT-SWAP");
        ContractCandidate ineligible = ineligibleCandidate("BLOCKED-USDT-SWAP");

        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        scores.put("WAITACT-USDT-SWAP", score("10"));
        scores.put("BLOCKED-USDT-SWAP", score("99"));

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                List.of(ineligible, waitAction), scores, properties);

        assertThat(delivered).extracting(ContractCandidate::instId)
                .containsExactly("WAITACT-USDT-SWAP", "BLOCKED-USDT-SWAP");
    }

    @Test
    @DisplayName("已存在等待意图的合约必定进入交付列表，否则它永远得不到触发评估")
    void pinnedWaitingIntentInstIdIsAlwaysDelivered() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        List<ContractCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candidates.add(eligibleCandidate("OK" + i + "-USDT-SWAP"));
        }
        // 排名垫底，而且当前完全不可交易：没有 pin 就一定被挤出交付列表。
        ContractCandidate waiting = ineligibleCandidate("WAITING-USDT-SWAP");
        candidates.add(waiting);

        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        for (ContractCandidate candidate : candidates) {
            scores.put(candidate.instId(), score("80"));
        }
        scores.put("WAITING-USDT-SWAP", score("1"));

        List<ContractCandidate> withoutPin =
                OkxContractScanner.rankForDelivery(candidates, scores, properties);
        assertThat(withoutPin).extracting(ContractCandidate::instId)
                .as("前提：不 pin 的话它确实拿不到交付名额")
                .doesNotContain("WAITING-USDT-SWAP");

        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                candidates, scores, properties, java.util.Set.of("WAITING-USDT-SWAP"));

        assertThat(delivered).hasSize(10);
        assertThat(delivered).extracting(ContractCandidate::instId)
                .as("等待意图必须每轮拿到失效判断和触发判断的数据")
                .contains("WAITING-USDT-SWAP");
    }

    @Test
    @DisplayName("pin 只给交付名额，不改变任何门控结论")
    void pinnedDeliveryDoesNotRelaxAnyGate() {
        AgentProperties properties = new AgentProperties();
        properties.setTopCandidateLimit(10);

        ContractCandidate waiting = ineligibleCandidate("WAITING-USDT-SWAP");
        List<ContractCandidate> delivered = OkxContractScanner.rankForDelivery(
                List.of(waiting), uniformScores(List.of(waiting)), properties,
                java.util.Set.of("WAITING-USDT-SWAP"));

        assertThat(delivered).hasSize(1);
        assertThat(com.example.quant.agent.gate.ContractTradeGate.planDenyReasons(delivered.get(0), properties))
                .as("进入交付列表不等于通过计划门控")
                .isNotEmpty();
        assertThat(com.example.quant.agent.gate.ContractTradeGate.autoDenyReasons(delivered.get(0), properties))
                .as("进入交付列表不等于动作条件就绪")
                .isNotEmpty();
        assertThat(com.example.quant.agent.gate.ContractTradeGate.aiHardDenyReasons(delivered.get(0), properties))
                .as("pin 不得改变 AI 硬门控的结论")
                .isEqualTo(com.example.quant.agent.gate.ContractTradeGate.aiHardDenyReasons(waiting, properties));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static Map<String, V2SelectionScoreResult> uniformScores(List<ContractCandidate> candidates) {
        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        for (ContractCandidate candidate : candidates) {
            scores.put(candidate.instId(), score("70"));
        }
        return scores;
    }

    private static Map<String, V2SelectionScoreResult> scoresRankingEligibleLast(
            List<ContractCandidate> candidates, String eligibleInstId) {
        Map<String, V2SelectionScoreResult> scores = new LinkedHashMap<>();
        int rank = 99;
        for (ContractCandidate candidate : candidates) {
            scores.put(candidate.instId(),
                    candidate.instId().equals(eligibleInstId) ? score("1") : score(String.valueOf(rank--)));
        }
        return scores;
    }

    private static V2SelectionScoreResult score(String value) {
        BigDecimal normalized = new BigDecimal(value);
        return new V2SelectionScoreResult(normalized, normalized, BigDecimal.valueOf(100),
                BigDecimal.ONE, Map.of(), List.of());
    }

    /** 通过 {@link com.example.quant.agent.gate.ContractTradeGate#planDenyReasons} 的候选。 */
    private static ContractCandidate eligibleCandidate(String instId) {
        return candidate(instId, ContractSignalType.STRONG_LONG, "AUTO_TRADE_ALLOWED",
                80, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80), new BigDecimal("2.5"));
    }

    /** 无法通过规则资格的"仅可观察"候选。 */
    private static ContractCandidate ineligibleCandidate(String instId) {
        return candidate(instId, ContractSignalType.WAIT_OVERHEATED, "WAIT",
                80, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80), new BigDecimal("2.5"));
    }

    /**
     * 计划门控全部通过、但动作条件仍未就绪的候选。
     *
     * <p>{@code planDenyReasons} 不检查 {@code action}，{@code autoDenyReasons} 才检查。
     * 这正是"计划合格 ≠ 立即可交易"的那一类候选。
     */
    private static ContractCandidate planEligibleButWaitingCandidate(String instId) {
        return candidate(instId, ContractSignalType.PULLBACK_LONG, "WAIT_PULLBACK",
                80, new ContractFactorScore(80, 80, 80, 80, 80, 80, 80), new BigDecimal("2.5"));
    }

    private static ContractCandidate candidate(String instId, ContractSignalType signalType, String action,
                                               int score, ContractFactorScore factorScore,
                                               BigDecimal riskReward) {
        ContractKlineAnalysis kline = new ContractKlineAnalysis(
                "UP", "UP", "UP", "BULLISH", "STRENGTHENING", BigDecimal.valueOf(60),
                BigDecimal.valueOf(25), "HIGHER_HIGH_HIGHER_LOW", "READY", BigDecimal.valueOf(1.5),
                BigDecimal.valueOf(2), BigDecimal.ONE, true);
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, instId.substring(0, instId.indexOf('-')), "USDT",
                BigDecimal.TEN, new BigDecimal("5"), BigDecimal.ONE, new BigDecimal("500000000"),
                new BigDecimal("2"), new BigDecimal("0.0001"), BigDecimal.ONE, BigDecimal.ONE,
                DirectionBias.BULLISH, new BigDecimal("0.02"), score, factorScore,
                BigDecimal.TEN, new BigDecimal("9.7"), new BigDecimal("12"), 3, riskReward,
                new BigDecimal("2"), new BigDecimal("200000"), new BigDecimal("200000"), BigDecimal.ONE,
                DirectionBias.BULLISH, DirectionBias.BULLISH, RiskLevel.LOW, List.of(), List.of(), NOW,
                signalType, action, "LIMIT", new BigDecimal("5"), new BigDecimal("0.02"),
                new BigDecimal("3"), BigDecimal.valueOf(80),
                new ContractScoreBreakdown(80, 80, 80, 80, 80, 80, 80), kline,
                ContractNewsRiskAnalysis.low(), null, null, null, new BigDecimal("0.5"), "FRESH",
                null, null, null, null);
    }
}
