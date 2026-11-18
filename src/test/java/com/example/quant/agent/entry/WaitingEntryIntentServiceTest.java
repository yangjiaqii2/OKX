package com.example.quant.agent.entry;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.agent.selection.ContractSelectionDecision;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.dto.ContractFactorScore;
import com.example.quant.crypto.dto.ContractKlineAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskAnalysis;
import com.example.quant.crypto.dto.ContractNewsRiskDecision;
import com.example.quant.crypto.dto.ContractScoreBreakdown;
import com.example.quant.crypto.dto.ContractSignalType;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.risk.RiskLevel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class WaitingEntryIntentServiceTest {
    @Autowired
    private WaitingEntryIntentRepository repository;

    @Test
    void sameDecisionCreatesOnePersistentIntentAcrossServiceRestart() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        ContractCandidate candidate = candidate("WAIT-" + UUID.randomUUID() + "-USDT-SWAP",
                DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW");
        ContractSelectionDecision selection = selectedWait(candidate.instId(), DirectionBias.BULLISH, "WAIT_PULLBACK");

        WaitingEntryIntentService.Intent first = new WaitingEntryIntentService(repository).createOrGet(
                "alice", "AUTO_CONTRACT_V1", candidate, selection, "qd-stable", candle.plusSeconds(10));
        WaitingEntryIntentService.Intent duplicate = new WaitingEntryIntentService(repository).createOrGet(
                "alice", "AUTO_CONTRACT_V1", candidate, selection, "qd-stable", candle.plusSeconds(20));

        assertThat(first.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.intentId()).isEqualTo(first.intentId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void longPullbackTriggersOnlyOnNewerReadyClosedCandle() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        String instId = "LONG-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-long", candle.plusSeconds(10));

        assertThat(service.evaluate("alice", List.of(candidate(instId, DirectionBias.BULLISH, "READY", candle,
                "99.8", "98", RiskLevel.LOW, "LOW")), candle.plusSeconds(30))).isEmpty();

        Instant nextCandle = candle.plusSeconds(300);
        WaitingEntryIntentService.TriggeredIntent triggered = service.evaluate("alice", List.of(
                candidate(instId, DirectionBias.BULLISH, "READY", nextCandle,
                        "99.8", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(30)).orElseThrow();

        assertThat(triggered.instId()).isEqualTo(instId);
        assertThat(triggered.direction()).isEqualTo(DirectionBias.BULLISH);
        assertThat(triggered.clientOrderId()).isEqualTo("qd-long");
        assertThat(repository.findById(triggered.intentId()).orElseThrow().getStatus())
                .isEqualTo(WaitingEntryIntentStatus.TRIGGERED);
    }

    @Test
    void shortRetestTriggersAtOrAboveReferencePrice() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        String instId = "SHORT-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BEARISH, "WAIT_RETEST", candle, "100", "102", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BEARISH, "WAIT_RETEST"), "qd-short", candle.plusSeconds(10));

        Instant nextCandle = candle.plusSeconds(300);
        assertThat(service.evaluate("alice", List.of(candidate(instId, DirectionBias.BEARISH, "READY", nextCandle,
                "100.2", "102", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(20))).isPresent();
    }

    @Test
    void readyPullbackAllowsTwoTenthsPercentPriceToleranceButNoMore() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        Instant nextCandle = candle.plusSeconds(300);
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String acceptedId = "TOLERANCE-OK-" + UUID.randomUUID() + "-USDT-SWAP";
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(acceptedId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle,
                        "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(acceptedId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-tolerance-ok", candle);

        assertThat(service.evaluate("alice", List.of(candidate(acceptedId, DirectionBias.BULLISH, "READY",
                nextCandle, "100.20", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(1))).isPresent();

        String rejectedId = "TOLERANCE-NO-" + UUID.randomUUID() + "-USDT-SWAP";
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(rejectedId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle,
                        "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(rejectedId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-tolerance-no", candle);

        assertThat(service.evaluate("alice", List.of(candidate(rejectedId, DirectionBias.BULLISH, "READY",
                nextCandle, "100.21", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(1))).isEmpty();
    }

    @Test
    void matchingWaitTimingTriggersAfterTwoClosedCandlesButNotWhenNotReady() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String matchingId = "MATCH-" + UUID.randomUUID() + "-USDT-SWAP";
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(matchingId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle,
                        "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(matchingId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-matching", candle);

        Instant firstNewCandle = candle.plusSeconds(300);
        assertThat(service.evaluate("alice", List.of(candidate(matchingId, DirectionBias.BULLISH, "WAIT_PULLBACK",
                firstNewCandle, "100.10", "98", RiskLevel.LOW, "LOW")), firstNewCandle.plusSeconds(1))).isEmpty();

        Instant secondNewCandle = candle.plusSeconds(600);
        assertThat(service.evaluate("alice", List.of(candidate(matchingId, DirectionBias.BULLISH, "WAIT_PULLBACK",
                secondNewCandle, "100.10", "98", RiskLevel.LOW, "LOW")), secondNewCandle.plusSeconds(1))).isPresent();

        String notReadyId = "NOT-READY-" + UUID.randomUUID() + "-USDT-SWAP";
        service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(notReadyId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle,
                        "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(notReadyId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-not-ready", candle);
        assertThat(service.evaluate("alice", List.of(candidate(notReadyId, DirectionBias.BULLISH, "NOT_READY",
                secondNewCandle, "99.90", "98", RiskLevel.LOW, "LOW")), secondNewCandle.plusSeconds(1))).isEmpty();
    }

    @Test
    void triggeredIntentIsRecoveredAfterServiceRestartWithoutIncrementingTriggerCount() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        Instant nextCandle = candle.plusSeconds(300);
        String instId = "RECOVER-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        WaitingEntryIntentService.Intent intent = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-recover", candle.plusSeconds(10));

        service.evaluate("alice", List.of(candidate(instId, DirectionBias.BULLISH, "READY", nextCandle,
                "99.8", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(20)).orElseThrow();
        WaitingEntryIntentService.TriggeredIntent recovered = new WaitingEntryIntentService(repository)
                .evaluate("alice", List.of(candidate(instId, DirectionBias.BULLISH, "READY", nextCandle,
                        "99.8", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(30)).orElseThrow();

        assertThat(recovered.intentId()).isEqualTo(intent.intentId());
        assertThat(recovered.clientOrderId()).isEqualTo("qd-recover");
        assertThat(repository.findById(intent.intentId()).orElseThrow().getTriggerCount()).isEqualTo(1);
    }

    @Test
    void waitingIntentSurvivesRoundsWhereItsSymbolIsAbsentFromTheDeliveredCandidateList() {
        // 候选交付列表有上限，等待中的标的可能在某一轮排不进来。
        // 这不代表意图失效：不得因为"本轮看不到它"就把等待候选终止掉。
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        String instId = "ABSENT-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String intentId = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-absent",
                candle.plusSeconds(10)).intentId();

        Instant nextCandle = candle.plusSeconds(300);
        assertThat(service.evaluate("alice", List.of(
                candidate("OTHER-" + UUID.randomUUID() + "-USDT-SWAP", DirectionBias.BULLISH, "READY",
                        nextCandle, "50", "49", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(30)))
                .as("标的本轮缺席时不得触发")
                .isEmpty();

        assertThat(repository.findById(intentId).orElseThrow().getStatus())
                .as("标的本轮缺席时等待状态必须原样保留")
                .isEqualTo(WaitingEntryIntentStatus.WAITING);

        // 标的重新出现并满足触发条件时，等待生命周期继续正常推进。
        WaitingEntryIntentService.TriggeredIntent triggered = service.evaluate("alice", List.of(
                candidate(instId, DirectionBias.BULLISH, "READY", nextCandle,
                        "99.8", "98", RiskLevel.LOW, "LOW")), nextCandle.plusSeconds(60)).orElseThrow();

        assertThat(triggered.intentId()).isEqualTo(intentId);
        assertThat(triggered.clientOrderId()).isEqualTo("qd-absent");
    }

    @Test
    void absentCandidateIsNotTriggeredOrInvalidatedAndOnlyExpiresByWallClock() {
        // evaluate() 找不到候选就 continue：触发和新闻/价格失效都不会跑。
        // 记录还在 ≠ 持续评估。壁钟到期是唯一不依赖行情数据的终止路径。
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        String instId = "NODATA-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String intentId = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-nodata",
                candle).intentId();

        Instant wouldHaveTriggeredAt = candle.plusSeconds(300);
        assertThat(service.evaluate("alice", List.of(), wouldHaveTriggeredAt.plusSeconds(1)))
                .as("没有候选数据时，即使时间已越过决策K线，也不得触发")
                .isEmpty();
        assertThat(repository.findById(intentId).orElseThrow().getStatus())
                .as("没有候选数据时，新闻或价格失效也无从判断，状态必须保持 WAITING")
                .isEqualTo(WaitingEntryIntentStatus.WAITING);

        assertThat(service.evaluate("alice", List.of(), candle.plusSeconds(1801))).isEmpty();
        assertThat(repository.findById(intentId).orElseThrow().getStatus())
                .as("壁钟到期不依赖交付列表，仍应终止")
                .isEqualTo(WaitingEntryIntentStatus.EXPIRED);
    }

    @Test
    void activeInstIdsExposeEveryUnterminatedIntentSoTheScannerCanKeepRefreshingThem() {
        // evaluate() 对"不在交付列表里"的标的直接 continue：既不触发也不失效。
        // 所以等待机制能否工作，取决于扫描层能不能知道"哪些标的现在正在等"。
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);

        String waiting = "ACTIVE-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService.Intent active = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(waiting, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(waiting, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-active", candle.plusSeconds(10));

        String done = "DONE-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService.Intent finished = service.createOrGet("bob", "AUTO_CONTRACT_V1",
                candidate(done, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(done, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-done", candle.plusSeconds(10));
        service.complete(finished.intentId(), WaitingEntryIntentStatus.SUBMITTED, null, null, "done",
                candle.plusSeconds(20));

        assertThat(service.activeInstIds())
                .as("未终结的等待意图必须对扫描层可见，且跨 owner 汇总")
                .contains(waiting)
                .doesNotContain(done);
        assertThat(active.created()).isTrue();
    }

    @Test
    void expiresOrInvalidatesWithoutTriggering() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String expiredId = "EXP-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService.Intent expired = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(expiredId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(expiredId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-expired", candle);

        assertThat(service.evaluate("alice", List.of(candidate(expiredId, DirectionBias.BULLISH, "NOT_READY",
                candle.plusSeconds(900), "99", "98", RiskLevel.LOW, "LOW")), candle.plusSeconds(901))).isEmpty();
        assertThat(repository.findById(expired.intentId()).orElseThrow().getStatus())
                .isEqualTo(WaitingEntryIntentStatus.WAITING);

        assertThat(service.evaluate("alice", List.of(candidate(expiredId, DirectionBias.BULLISH, "READY",
                candle.plusSeconds(1800), "99", "98", RiskLevel.LOW, "LOW")), candle.plusSeconds(1801))).isEmpty();
        assertThat(repository.findById(expired.intentId()).orElseThrow().getStatus())
                .isEqualTo(WaitingEntryIntentStatus.EXPIRED);

        String riskyId = "RISK-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService.Intent risky = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(riskyId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(riskyId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-risk", candle);
        service.evaluate("alice", List.of(candidate(riskyId, DirectionBias.BULLISH, "READY",
                candle.plusSeconds(300), "99", "98", RiskLevel.HIGH, "HIGH")), candle.plusSeconds(301));

        assertThat(repository.findById(risky.intentId()).orElseThrow().getStatus())
                .isEqualTo(WaitingEntryIntentStatus.INVALIDATED);
    }

    @Test
    void invalidatesWaitingIntentWhenNewsBecomesUnavailable() {
        Instant candle = Instant.parse("2026-08-12T08:00:00Z");
        WaitingEntryIntentService service = new WaitingEntryIntentService(repository);
        String instId = "UNKNOWN-" + UUID.randomUUID() + "-USDT-SWAP";
        WaitingEntryIntentService.Intent intent = service.createOrGet("alice", "AUTO_CONTRACT_V1",
                candidate(instId, DirectionBias.BULLISH, "WAIT_PULLBACK", candle, "100", "98", RiskLevel.LOW, "LOW"),
                selectedWait(instId, DirectionBias.BULLISH, "WAIT_PULLBACK"), "qd-unknown", candle);

        assertThat(service.evaluate("alice", List.of(candidate(instId, DirectionBias.BULLISH, "READY",
                candle.plusSeconds(300), "99", "98", RiskLevel.LOW, "UNKNOWN")), candle.plusSeconds(301)))
                .isEmpty();

        WaitingEntryIntentEntity recovered = repository.findById(intent.intentId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(WaitingEntryIntentStatus.INVALIDATED);
        assertThat(recovered.getReason()).isEqualTo("AI_WAIT_INVALIDATED_NEWS_UNAVAILABLE");
    }

    private static ContractSelectionDecision selectedWait(String instId, DirectionBias direction, String mode) {
        return new ContractSelectionDecision(
                ContractSelectionDecision.DecisionSource.AI_WAIT,
                ContractSelectionDecision.SelectionAction.WAIT,
                instId,
                direction,
                mode,
                new BigDecimal("0.75"),
                List.of(new ContractSelectionDecision.RankedCandidate(instId, 1, direction, mode,
                        new BigDecimal("0.75"))),
                List.of("等待更优入场"),
                List.of(),
                ""
        );
    }

    private static ContractCandidate candidate(String instId, DirectionBias direction, String timing, Instant candle,
                                               String lastPrice, String stopPrice, RiskLevel riskLevel,
                                               String newsRisk) {
        ContractFactorScore factors = new ContractFactorScore(20, 20, 8, 15, 8, 8, 7);
        ContractKlineAnalysis kline = new ContractKlineAnalysis(
                direction == DirectionBias.BEARISH ? "DOWN" : "UP",
                direction == DirectionBias.BEARISH ? "DOWN" : "UP",
                direction == DirectionBias.BEARISH ? "DOWN" : "UP",
                "ALIGNED", "CONFIRMED", new BigDecimal("50"), new BigDecimal("25"),
                "TREND", timing, BigDecimal.ONE, new BigDecimal("2"), BigDecimal.ZERO, true, candle);
        ContractNewsRiskAnalysis news = new ContractNewsRiskAnalysis(
                "LOW".equals(newsRisk) ? 90 : 10, newsRisk, List.of(), List.of(), List.of(),
                "LOW".equals(newsRisk)
                        ? ContractNewsRiskDecision.allow("test")
                        : ContractNewsRiskDecision.noTrade("test"));
        return new ContractCandidate(
                MarketType.OKX_SWAP, instId, "TEST", "USDT", new BigDecimal(lastPrice),
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("500000000"), BigDecimal.ONE,
                new BigDecimal("0.0001"), new BigDecimal("1000000"), BigDecimal.ONE, direction,
                new BigDecimal("0.02"), 90, factors, new BigDecimal(lastPrice), new BigDecimal(stopPrice),
                direction == DirectionBias.BEARISH ? new BigDecimal("96") : new BigDecimal("104"),
                3, new BigDecimal("2"), BigDecimal.ONE, new BigDecimal("500000"),
                new BigDecimal("500000"), new BigDecimal("0.5"), DirectionBias.BULLISH,
                DirectionBias.BULLISH, riskLevel, List.of(), List.of(), candle, ContractSignalType.STRONG_LONG,
                "AUTO_TRADE_ALLOWED", timing, BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("2"),
                new BigDecimal("90"), new ContractScoreBreakdown(20, 20, 8, 15, 8, 8, 7), kline, news);
    }
}
