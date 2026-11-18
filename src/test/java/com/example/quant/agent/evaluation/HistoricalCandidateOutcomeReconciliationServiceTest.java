package com.example.quant.agent.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.quant.agent.score.AgentContractCandidateScoreEntity;
import com.example.quant.agent.score.AgentContractCandidateScoreRepository;
import com.example.quant.crypto.OkxMarketService;
import com.example.quant.crypto.dto.ContractCandle;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.PageRequest;

class HistoricalCandidateOutcomeReconciliationServiceTest {
    @Test
    void rateLimitDefersHistoryAcrossRunsButRecentOutcomesStillComplete() {
        var snapshots = mock(AgentContractCandidateScoreRepository.class);
        var outcomes = mock(CandidateFutureOutcomeRepository.class);
        var market = mock(OkxMarketService.class);
        Instant now = Instant.parse("2026-11-09T11:05:00Z");
        Instant maturity = Instant.parse("2026-09-18T11:00:00Z");
        var first = pending(6L, 60, maturity);
        var next = pending(7L, 60, maturity);
        var recent = pending(8L, 60, Instant.parse("2026-11-09T11:00:00Z"));
        var recentSnapshot = snapshot(8L, "SOL-USDT-SWAP", "LONG");
        recentSnapshot.setCreatedAt(Instant.parse("2026-11-09T10:00:00Z"));
        when(outcomes.findHistoricalDue(any(), any(), any(), any())).thenReturn(List.of(first, next));
        when(snapshots.findById(6L)).thenReturn(Optional.of(snapshot(6L, "BTC-USDT-SWAP", "LONG")));
        when(snapshots.findById(7L)).thenReturn(Optional.of(snapshot(7L, "ETH-USDT-SWAP", "LONG")));
        when(snapshots.findById(8L)).thenReturn(Optional.of(recentSnapshot));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(), List.of(recent));
        when(market.candles("SOL-USDT-SWAP", "15m", 300)).thenReturn(IntStream.range(0, 4)
                .mapToObj(i -> new ContractCandle(recentSnapshot.getCreatedAt().plusSeconds(i * 900L),
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE))
                .toList());
        when(market.historicalCandlesBefore("BTC-USDT-SWAP", "15m", maturity))
                .thenThrow(new OkxMarketService.HistoryRateLimitedException(new IllegalStateException("OKX HTTP 429")));
        var service = new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.fixed(now, ZoneOffset.UTC), 100, 0);

        assertThat(service.reconcile(now).failed()).isEqualTo(1);
        assertThat(first.getErrorReason()).isEqualTo("HISTORY_RATE_LIMIT_HTTP_429");
        assertThat(first.getNextRetryAt()).isEqualTo(now.plusSeconds(300));
        assertThat(next.getStatus()).isEqualTo("PENDING");
        assertThat(service.reconcile(now.plusSeconds(120)).completed()).isEqualTo(1);
        assertThat(recent.getStatus()).isEqualTo("COMPLETE");
        verify(market, times(1)).historicalCandlesBefore(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"100,true", "99,true", "101,false"})
    void pullbackTouchUsesTheReferenceAndOnlyTheCompleteAfterSnapshotWindow(String lowestPrice, boolean touched) {
        var snapshots = mock(AgentContractCandidateScoreRepository.class);
        var outcomes = mock(CandidateFutureOutcomeRepository.class);
        var market = mock(OkxMarketService.class);
        var snapshot = snapshot(22L, "BTC-USDT-SWAP", "LONG");
        snapshot.setSignalType("PULLBACK_LONG");
        var outcome = pending(22L, 60, Instant.parse("2026-09-18T11:00:00Z"));
        when(snapshots.findById(22L)).thenReturn(Optional.of(snapshot));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(outcome));
        List<ContractCandle> candles = new java.util.ArrayList<>(oneHourWindow("107", lowestPrice, "103"));
        candles.add(candle("2026-09-18T09:45:00Z", "100", "101", "98", "100"));
        candles.add(candle("2026-09-18T11:00:00Z", "100", "101", "98", "100"));
        when(market.candles("BTC-USDT-SWAP", "15m", 300)).thenReturn(candles);

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market, Clock.systemUTC())
                .reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(outcome.getStatus()).isEqualTo("COMPLETE");
        assertThat(outcome.getPullbackReferenceTouched()).isEqualTo(touched);
    }

    @Test
    void reservesBothQueuesAndRecoversAnOldOutcomeOutsideTheRollingCandleWindow() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        CandidateFutureOutcomeEntity old = pending(6L, 60, Instant.parse("2026-09-18T11:00:00Z"));
        old.setStatus("PARTIAL");
        Instant now = Instant.parse("2026-11-09T11:05:00Z");
        Instant cutoff = Instant.parse("2026-11-07T11:05:00Z");
        CandidateFutureOutcomeEntity recent = pending(17L, 60, Instant.parse("2026-11-09T11:00:00Z"));
        AgentContractCandidateScoreEntity recentSnapshot = snapshot(17L, "BTC-USDT-SWAP", "LONG");
        recentSnapshot.setCreatedAt(Instant.parse("2026-11-09T10:00:00Z"));
        Set<String> statuses = Set.of("PENDING", "PARTIAL", "FAILED");
        when(outcomes.findHistoricalDue(statuses, cutoff, now, PageRequest.of(0, 1))).thenReturn(List.of(old));
        when(outcomes.findRecentDue(statuses, cutoff, now, PageRequest.of(0, 4))).thenReturn(List.of(recent));
        when(snapshots.findById(6L)).thenReturn(Optional.of(snapshot(6L, "BTC-USDT-SWAP", "LONG")));
        when(snapshots.findById(17L)).thenReturn(Optional.of(recentSnapshot));
        when(market.historicalCandlesBefore("BTC-USDT-SWAP", "15m", old.getMaturityAt()))
                .thenReturn(oneHourWindow("105", "98", "103"));
        Duration dateOffset = Duration.between(snapshot(6L, "BTC-USDT-SWAP", "LONG").getCreatedAt(),
                recentSnapshot.getCreatedAt());
        when(market.candles("BTC-USDT-SWAP", "15m", 300)).thenReturn(oneHourWindow("106", "98", "104")
                .stream().map(candle -> new ContractCandle(
                        candle.timestamp().plus(dateOffset), candle.open(), candle.high(),
                        candle.low(), candle.close(), candle.volume())).toList());

        var result = new HistoricalCandidateOutcomeReconciliationService(
                snapshots, outcomes, market, Clock.systemUTC(), 5, 300_000L).reconcile(now);

        assertThat(result.completed()).isEqualTo(2);
        assertThat(old.getStatus()).isEqualTo("COMPLETE");
        assertThat(old.getFuturePrice()).isEqualByComparingTo("103");
        assertThat(recent.getStatus()).isEqualTo("COMPLETE");
        assertThat(recent.getFuturePrice()).isEqualByComparingTo("104");
        verify(market).historicalCandlesBefore("BTC-USDT-SWAP", "15m", old.getMaturityAt());
        verify(market).candles("BTC-USDT-SWAP", "15m", 300);
    }

    @Test
    void anchoredPageCoversAFullDayWithoutUsingBarsAfterMaturity() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(20L, "BTC-USDT-SWAP", "LONG");
        snapshot.setCreatedAt(Instant.parse("2026-09-18T10:01:00Z"));
        CandidateFutureOutcomeEntity outcome = pending(20L, 1440, Instant.parse("2026-09-19T10:01:00Z"));
        Instant anchor = Instant.parse("2026-09-19T11:00:00Z");
        Instant expectedEnd = Instant.parse("2026-09-19T10:00:00Z");
        var close = new BigDecimal("103");
        var laterClose = new BigDecimal("115");
        List<ContractCandle> page = IntStream.rangeClosed(1, 100)
                .mapToObj(offset -> anchor.minus(Duration.ofMinutes(15L * offset)))
                .map(start -> new ContractCandle(start, BigDecimal.valueOf(100), laterClose, BigDecimal.valueOf(98),
                        start.isBefore(expectedEnd) ? close : laterClose, BigDecimal.ONE))
                .toList();
        when(outcomes.findHistoricalDue(any(), any(), any(), any())).thenReturn(List.of(outcome));
        when(snapshots.findById(20L)).thenReturn(Optional.of(snapshot));
        when(market.historicalCandlesBefore("BTC-USDT-SWAP", "15m", anchor)).thenReturn(page);

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market, Clock.systemUTC())
                .reconcile(Instant.parse("2026-11-09T11:05:00Z"));

        assertThat(outcome.getStatus()).isEqualTo("COMPLETE");
        assertThat(outcome.getWindowStart()).isEqualTo(Instant.parse("2026-09-18T10:15:00Z"));
        assertThat(outcome.getWindowEnd()).isEqualTo(expectedEnd);
        assertThat(outcome.getFuturePrice()).isEqualByComparingTo(close);
    }

    @Test
    void sharesHistoricalPageAcrossNearbyMaturitiesAndKeepsGapsRetryable() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        CandidateFutureOutcomeEntity complete = pending(18L, 60, Instant.parse("2026-09-18T11:00:00Z"));
        CandidateFutureOutcomeEntity gap = pending(19L, 60, Instant.parse("2026-09-18T10:55:00Z"));
        AgentContractCandidateScoreEntity earlier = snapshot(19L, "BTC-USDT-SWAP", "LONG");
        earlier.setCreatedAt(Instant.parse("2026-09-18T09:55:00Z"));
        when(outcomes.findHistoricalDue(any(), any(), any(), any())).thenReturn(List.of(complete, gap));
        when(snapshots.findById(18L)).thenReturn(Optional.of(snapshot(18L, "BTC-USDT-SWAP", "LONG")));
        when(snapshots.findById(19L)).thenReturn(Optional.of(earlier));
        when(market.historicalCandlesBefore("BTC-USDT-SWAP", "15m", complete.getMaturityAt()))
                .thenReturn(List.of(candle("2026-09-18T10:00:00Z", "100", "105", "98", "103"),
                        candle("2026-09-18T10:45:00Z", "100", "105", "98", "103")));

        var result = new HistoricalCandidateOutcomeReconciliationService(
                snapshots, outcomes, market, Clock.systemUTC()).reconcile(Instant.parse("2026-11-09T11:05:00Z"));

        assertThat(result.partial()).isEqualTo(2);
        assertThat(gap.getErrorReason()).isEqualTo("FUTURE_CANDLE_GAP");
        assertThat(gap.getNextRetryAt()).isNotNull();
        assertThat(gap.getFuturePrice()).isNull();
        verify(market).historicalCandlesBefore("BTC-USDT-SWAP", "15m", complete.getMaturityAt());
    }

    @Test
    void doesNotCompleteBeforeMaturityThenPersistsDecimalLongOutcome() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(7L, "BTC-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity oneHour = pending(7L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(7L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("BTC-USDT-SWAP"), eq("15m"), eq(300))).thenReturn(List.of(
                candle("2026-09-18T10:00:00Z", "100", "105", "98", "102"),
                candle("2026-09-18T10:15:00Z", "102", "115", "92", "105"),
                candle("2026-09-18T10:30:00Z", "105", "108", "96", "106"),
                candle("2026-09-18T10:45:00Z", "105", "110", "97", "110")
        ));
        Clock clock = Clock.fixed(Instant.parse("2026-09-18T10:30:00Z"), ZoneOffset.UTC);
        HistoricalCandidateOutcomeReconciliationService service =
                new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market, clock);

        service.reconcile(clock.instant());

        assertThat(oneHour.getStatus()).isEqualTo("PENDING");
        assertThat(oneHour.getRawReturn()).isNull();
        verifyNoInteractions(market);

        service.reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(oneHour.getStatus()).isEqualTo("COMPLETE");
        assertThat(oneHour.getFuturePrice()).isEqualByComparingTo("110");
        assertThat(oneHour.getRawReturn()).isEqualByComparingTo("0.10");
        assertThat(oneHour.getMaxUpside()).isEqualByComparingTo("0.15");
        assertThat(oneHour.getMaxDownside()).isEqualByComparingTo("-0.08");
        assertThat(oneHour.getDirectionalMfe()).isEqualByComparingTo("0.15");
        assertThat(oneHour.getDirectionalMae()).isEqualByComparingTo("0.08");
        verify(market, times(1)).candles("BTC-USDT-SWAP", "15m", 300);
    }

    @Test
    void missingFutureCandleRemainsRetryableWithoutSyntheticZero() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(8L, "ETH-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity oneHour = pending(8L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(8L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("ETH-USDT-SWAP"), eq("15m"), eq(300))).thenReturn(List.of());

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(oneHour.getStatus()).isEqualTo("PARTIAL");
        assertThat(oneHour.getRawReturn()).isNull();
        assertThat(oneHour.getErrorReason()).isEqualTo("FUTURE_CANDLE_MISSING");
    }

    @Test
    void shortOutcomeKeepsRawReturnAndDerivesOppositeDirectionalMetrics() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(9L, "SOL-USDT-SWAP", "SHORT");
        CandidateFutureOutcomeEntity oneHour = pending(9L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(9L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("SOL-USDT-SWAP"), eq("15m"), eq(300)))
                .thenReturn(oneHourWindow("115", "92", "105"));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(oneHour.getRawReturn()).isEqualByComparingTo("0.05");
        assertThat(oneHour.getDirectionalReturn()).isEqualByComparingTo("-0.05");
        assertThat(oneHour.getDirectionalMfe()).isEqualByComparingTo("0.08");
        assertThat(oneHour.getDirectionalMae()).isEqualByComparingTo("0.15");
        assertThat(oneHour.getFirstBarrierTouch()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void sameCandleTouchesStopFirstAndDrawdownUsesTheWholeWindow() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(21L, "BTC-USDT-SWAP", "LONG");
        snapshot.setSnapshotStopLossPrice(new BigDecimal("95"));
        snapshot.setSnapshotTakeProfitPrice(new BigDecimal("105"));
        CandidateFutureOutcomeEntity outcome = pending(21L, 60, Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(outcome));
        when(snapshots.findById(21L)).thenReturn(Optional.of(snapshot));
        when(market.candles("BTC-USDT-SWAP", "15m", 300)).thenReturn(List.of(
                candle("2026-09-18T09:45:00Z", "100", "200", "1", "100"),
                candle("2026-09-18T10:00:00Z", "100", "110", "92", "103"),
                candle("2026-09-18T10:15:00Z", "103", "108", "97", "104"),
                candle("2026-09-18T10:30:00Z", "104", "120", "101", "112"),
                candle("2026-09-18T10:45:00Z", "112", "117", "99", "108")));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market, Clock.systemUTC())
                .reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(outcome.getFirstBarrierTouch()).isEqualTo("STOP_LOSS");
        assertThat(outcome.getDirectionalMfe()).isEqualByComparingTo("0.20");
        assertThat(outcome.getDirectionalMaxDrawdown()).isEqualByComparingTo("0.21");
        assertThat(outcome.getDirectionalReturn()).isEqualByComparingTo("0.08");
    }

    @Test
    void oneInstrumentFetchServesMultipleMatureHorizons() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(10L, "XRP-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity first = pending(10L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        CandidateFutureOutcomeEntity second = pending(10L, 240,
                Instant.parse("2026-09-18T12:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(first, second));
        when(snapshots.findById(10L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("XRP-USDT-SWAP"), eq("15m"), eq(300))).thenReturn(List.of(
                candle("2026-09-18T10:00:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T10:15:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T10:30:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T10:45:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T11:00:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T11:15:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T11:30:00Z", "100", "101", "99", "100"),
                candle("2026-09-18T11:45:00Z", "100", "103", "98", "102")
        ));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T12:05:00Z"));

        verify(market, times(1)).candles("XRP-USDT-SWAP", "15m", 300);
        assertThat(first.getStatus()).isEqualTo("COMPLETE");
        assertThat(second.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void middleOfCandleUsesOnlyFullyClosedCandlesInsideWindow() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(11L, "ADA-USDT-SWAP", "LONG");
        snapshot.setCreatedAt(Instant.parse("2026-09-18T10:07:00Z"));
        CandidateFutureOutcomeEntity oneHour = pending(11L, 60,
                Instant.parse("2026-09-18T11:07:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(11L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("ADA-USDT-SWAP"), eq("15m"), eq(300))).thenReturn(List.of(
                candle("2026-09-18T10:00:00Z", "100", "999", "1", "101"),
                candle("2026-09-18T10:15:00Z", "101", "105", "98", "102"),
                candle("2026-09-18T10:30:00Z", "102", "110", "97", "104"),
                candle("2026-09-18T10:45:00Z", "104", "115", "92", "110"),
                candle("2026-09-18T11:00:00Z", "110", "999", "1", "120")
        ));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T11:20:00Z"));

        assertThat(oneHour.getFutureTimestamp()).isEqualTo(Instant.parse("2026-09-18T10:45:00Z"));
        assertThat(oneHour.getFuturePrice()).isEqualByComparingTo("110");
        assertThat(oneHour.getMaxUpside()).isEqualByComparingTo("0.15");
        assertThat(oneHour.getMaxDownside()).isEqualByComparingTo("-0.08");
        assertThat(oneHour.getWindowStart()).isEqualTo(Instant.parse("2026-09-18T10:15:00Z"));
        assertThat(oneHour.getWindowEnd()).isEqualTo(Instant.parse("2026-09-18T11:00:00Z"));
    }

    @Test
    void doesNotCompleteWhenOnlyOneBarRepresentsAnIncompleteHorizonWindow() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(16L, "ATOM-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity oneHour = pending(16L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(16L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("ATOM-USDT-SWAP"), eq("15m"), eq(300))).thenReturn(List.of(
                candle("2026-09-18T10:45:00Z", "100", "105", "98", "103")
        ));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(oneHour.getStatus()).isEqualTo("PARTIAL");
        assertThat(oneHour.getRawReturn()).isNull();
        assertThat(oneHour.getErrorReason()).isEqualTo("FUTURE_CANDLE_GAP");
    }

    @Test
    void completeOutcomeIsImmutableOnLaterReconciliation() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(12L, "DOGE-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity complete = pending(12L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        complete.setStatus("COMPLETE");
        complete.setFutureTimestamp(Instant.parse("2026-09-18T10:45:00Z"));
        complete.setFuturePrice(new BigDecimal("110"));
        complete.setRawReturn(new BigDecimal("0.10"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(complete));
        when(snapshots.findById(12L)).thenReturn(Optional.of(snapshot));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T12:00:00Z"));

        verifyNoInteractions(market);
        assertThat(complete.getFuturePrice()).isEqualByComparingTo("110");
        assertThat(complete.getRawReturn()).isEqualByComparingTo("0.10");
    }

    @Test
    void failedMarketAttemptBacksOffThenCanRecover() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(13L, "AVAX-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity oneHour = pending(13L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(13L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("AVAX-USDT-SWAP"), eq("15m"), eq(300)))
                .thenThrow(new IllegalStateException("temporary timeout"))
                .thenReturn(oneHourWindow("105", "98", "103"));

        HistoricalCandidateOutcomeReconciliationService service =
                new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market, Clock.systemUTC());
        Instant firstAttempt = Instant.parse("2026-09-18T11:05:00Z");
        service.reconcile(firstAttempt);

        assertThat(oneHour.getStatus()).isEqualTo("FAILED");
        assertThat(oneHour.getRetryCount()).isEqualTo(1);
        assertThat(oneHour.getNextRetryAt()).isAfter(firstAttempt);

        service.reconcile(firstAttempt.plusSeconds(60));
        verify(market, times(1)).candles("AVAX-USDT-SWAP", "15m", 300);

        service.reconcile(firstAttempt.plusSeconds(301));
        assertThat(oneHour.getStatus()).isEqualTo("COMPLETE");
        assertThat(oneHour.getRetryCount()).isEqualTo(2);
        assertThat(oneHour.getNextRetryAt()).isNull();
        verify(market, times(2)).candles("AVAX-USDT-SWAP", "15m", 300);
    }

    @Test
    void unknownDirectionKeepsRawMetricsButLeavesDirectionalMetricsNull() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(14L, "XLM-USDT-SWAP", null);
        CandidateFutureOutcomeEntity oneHour = pending(14L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(14L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("XLM-USDT-SWAP"), eq("15m"), eq(300)))
                .thenReturn(oneHourWindow("105", "98", "103"));

        new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                Clock.systemUTC()).reconcile(Instant.parse("2026-09-18T11:05:00Z"));

        assertThat(oneHour.getRawReturn()).isEqualByComparingTo("0.03");
        assertThat(oneHour.getMaxUpside()).isEqualByComparingTo("0.05");
        assertThat(oneHour.getMaxDownside()).isEqualByComparingTo("-0.02");
        assertThat(oneHour.getDirectionalReturn()).isNull();
        assertThat(oneHour.getDirectionalMfe()).isNull();
        assertThat(oneHour.getDirectionalMae()).isNull();
    }

    @Test
    void partialOutcomeCanRecoverWhenTheMissingBarArrives() {
        AgentContractCandidateScoreRepository snapshots = mock(AgentContractCandidateScoreRepository.class);
        CandidateFutureOutcomeRepository outcomes = mock(CandidateFutureOutcomeRepository.class);
        OkxMarketService market = mock(OkxMarketService.class);
        AgentContractCandidateScoreEntity snapshot = snapshot(15L, "LINK-USDT-SWAP", "LONG");
        CandidateFutureOutcomeEntity oneHour = pending(15L, 60,
                Instant.parse("2026-09-18T11:00:00Z"));
        when(outcomes.findRecentDue(any(), any(), any(), any())).thenReturn(List.of(oneHour));
        when(snapshots.findById(15L)).thenReturn(Optional.of(snapshot));
        when(market.candles(eq("LINK-USDT-SWAP"), eq("15m"), eq(300)))
                .thenReturn(List.of(
                        candle("2026-09-18T10:00:00Z", "100", "101", "99", "100"),
                        candle("2026-09-18T10:30:00Z", "100", "103", "98", "102")))
                .thenReturn(List.of(
                        candle("2026-09-18T10:00:00Z", "100", "101", "99", "100"),
                        candle("2026-09-18T10:15:00Z", "100", "102", "99", "101"),
                        candle("2026-09-18T10:30:00Z", "101", "103", "98", "102"),
                        candle("2026-09-18T10:45:00Z", "102", "104", "100", "103")));
        HistoricalCandidateOutcomeReconciliationService service =
                new HistoricalCandidateOutcomeReconciliationService(snapshots, outcomes, market,
                        Clock.systemUTC(), 100, 0);

        service.reconcile(Instant.parse("2026-09-18T11:05:00Z"));
        assertThat(oneHour.getStatus()).isEqualTo("PARTIAL");
        service.reconcile(Instant.parse("2026-09-18T11:06:00Z"));
        assertThat(oneHour.getStatus()).isEqualTo("COMPLETE");
        assertThat(oneHour.getRetryCount()).isEqualTo(2);
    }

    @Test
    void rejectsUnsupportedOutcomeHorizon() {
        CandidateFutureOutcomeEntity outcome = new CandidateFutureOutcomeEntity();
        assertThatThrownBy(() -> outcome.setHorizonMinutes(30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentContractCandidateScoreEntity snapshot(Long id, String instId, String direction) {
        AgentContractCandidateScoreEntity entity = new AgentContractCandidateScoreEntity();
        entity.setId(id);
        entity.setInstId(instId);
        entity.setDirection(direction);
        entity.setReferencePrice(new BigDecimal("100"));
        entity.setCreatedAt(Instant.parse("2026-09-18T10:00:00Z"));
        return entity;
    }

    private static CandidateFutureOutcomeEntity pending(Long snapshotId, int horizon, Instant maturity) {
        CandidateFutureOutcomeEntity entity = new CandidateFutureOutcomeEntity();
        entity.setSnapshotId(snapshotId);
        entity.setHorizonMinutes(horizon);
        entity.setMaturityAt(maturity);
        entity.setStatus("PENDING");
        return entity;
    }

    private static ContractCandle candle(String time, String open, String high, String low, String close) {
        return new ContractCandle(Instant.parse(time), new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE);
    }

    private static List<ContractCandle> oneHourWindow(String high, String low, String close) {
        return List.of(
                candle("2026-09-18T10:00:00Z", "100", high, low, close),
                candle("2026-09-18T10:15:00Z", close, high, low, close),
                candle("2026-09-18T10:30:00Z", close, high, low, close),
                candle("2026-09-18T10:45:00Z", close, high, low, close)
        );
    }
}
