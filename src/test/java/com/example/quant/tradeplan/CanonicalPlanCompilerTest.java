package com.example.quant.tradeplan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.market.DirectionBias;
import com.example.quant.okxtrade.OkxInstrumentRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CanonicalPlanCompilerTest {
    private static final OkxInstrumentRules RULES = new OkxInstrumentRules(
            "XAU-USDT-SWAP", new BigDecimal("1"), "XAU",
            new BigDecimal("1"), new BigDecimal("1"), new BigDecimal("0.1"));

    @Test
    void rejectsLadderWhereFinalTargetIsBeforeTheFixedTwoRLeg() {
        TradeIntent intent = new TradeIntent(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "XAU-USDT-SWAP", TradePlanType.OPEN_LONG, "LIMIT",
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("118"),
                new BigDecimal("10"), new BigDecimal("1000"), "strategy-v1",
                List.of(
                        new TradeIntent.ExitLeg("TP1", new BigDecimal("1"), new BigDecimal("110"), new BigDecimal("0.30"), "first target"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), new BigDecimal("120"), new BigDecimal("0.40"), "second target"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("1.8"), new BigDecimal("118"), new BigDecimal("0.30"), "plan target")
                ));

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(intent, RULES);

        assertThat(result.status()).isEqualTo(CanonicalExecutablePlan.CompilationStatus.REJECTED);
        assertThat(result.decisions()).contains("TP_LADDER_NOT_MONOTONIC");
    }

    @Test
    void buildsValidDynamicLaddersForLongTargetsFromOnePointFiveToThreeR() {
        for (String target : List.of("1.5", "1.8", "2.0", "2.2", "3.0")) {
            TradePlan plan = new TradePlan(UUID.randomUUID(), "XAU-USDT-SWAP", TradePlanType.OPEN_LONG,
                    "LIMIT", DirectionBias.BULLISH, BigDecimal.ONE, new BigDecimal("100"),
                    new BigDecimal("90"), new BigDecimal(target).multiply(new BigDecimal("10")).add(new BigDecimal("100")),
                    2, new BigDecimal("10"), new BigDecimal("22.5"), new BigDecimal("100"),
                    new BigDecimal(target), 90, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100000000"),
                    "cross", List.of(), List.of(), "", true, java.time.Instant.now().plusSeconds(120));
            CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(
                    TradeIntent.fromPlan(plan, new BigDecimal("0.30"), new BigDecimal("0.40"), new BigDecimal("0.30"), "strategy-v1"), RULES);
            assertThat(result.status()).as(target).isEqualTo(CanonicalExecutablePlan.CompilationStatus.ACCEPTED);
            assertThat(result.takeProfitLegs()).extracting(CanonicalExecutablePlan.ExitLeg::executableTargetPrice)
                    .doesNotHaveDuplicates();
            assertThat(result.takeProfitLegs()).extracting(CanonicalExecutablePlan.ExitLeg::executableContractQuantity)
                    .containsExactlyElementsOf(result.takeProfitLegs().size() == 2
                            ? List.of(new BigDecimal("3"), new BigDecimal("7"))
                            : List.of(new BigDecimal("3"), new BigDecimal("4"), new BigDecimal("3")));
        }
    }

    @Test
    void buildsValidDynamicLaddersForShortTargetsAndKeepsDescendingPrices() {
        for (String target : List.of("1.5", "1.8", "2.0", "2.2", "3.0")) {
            TradePlan plan = new TradePlan(UUID.randomUUID(), "XAU-USDT-SWAP", TradePlanType.OPEN_SHORT,
                    "LIMIT", DirectionBias.BEARISH, BigDecimal.ONE, new BigDecimal("100"),
                    new BigDecimal("110"), new BigDecimal("100").subtract(new BigDecimal(target).multiply(new BigDecimal("10"))),
                    2, new BigDecimal("10"), new BigDecimal("22.5"), new BigDecimal("100"),
                    new BigDecimal(target), 90, BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("100000000"),
                    "cross", List.of(), List.of(), "", true, java.time.Instant.now().plusSeconds(120));
            CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(
                    TradeIntent.fromPlan(plan, new BigDecimal("0.30"), new BigDecimal("0.40"), new BigDecimal("0.30"), "strategy-v1"), RULES);
            assertThat(result.status()).as(target).isEqualTo(CanonicalExecutablePlan.CompilationStatus.ACCEPTED);
            assertThat(result.takeProfitLegs()).extracting(CanonicalExecutablePlan.ExitLeg::executableTargetPrice)
                    .doesNotHaveDuplicates();
            for (int i = 1; i < result.takeProfitLegs().size(); i++) {
                assertThat(result.takeProfitLegs().get(i - 1).executableTargetPrice())
                        .isGreaterThan(result.takeProfitLegs().get(i).executableTargetPrice());
            }
        }
    }

    @Test
    void rejectsSmallPositionWhenAnyPlannedLegCannotMeetMinimumSize() {
        TradeIntent intent = intent(new BigDecimal("1"), DirectionBias.BULLISH);

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(intent, RULES);

        assertThat(result.status()).isEqualTo(CanonicalExecutablePlan.CompilationStatus.REJECTED);
        assertThat(result.decisions()).anyMatch(reason -> reason.contains("TP1") && reason.contains("MIN_SIZE"));
        assertThat(result.takeProfitLegs()).isEmpty();
    }

    @Test
    void usesConservativeDirectionalPriceRoundingAndIsDeterministic() {
        TradeIntent longIntent = intent(new BigDecimal("10"), DirectionBias.BULLISH);
        TradeIntent shortIntent = new TradeIntent(
                longIntent.planId(), longIntent.instId(), TradePlanType.OPEN_SHORT, "LIMIT",
                new BigDecimal("100.04"), new BigDecimal("110.06"), new BigDecimal("82.02"),
                longIntent.intendedBaseQuantity(), longIntent.riskBudget(), longIntent.strategyVersion(),
                List.of(
                        new TradeIntent.ExitLeg("TP1", new BigDecimal("1"), new BigDecimal("90.01"), new BigDecimal("0.30"), "first"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), new BigDecimal("80.01"), new BigDecimal("0.40"), "second"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("2.2"), new BigDecimal("78"), new BigDecimal("0.30"), "final")
                ));

        CanonicalPlanCompiler compiler = new CanonicalPlanCompiler();
        CanonicalExecutablePlan first = compiler.compile(longIntent, RULES);
        CanonicalExecutablePlan second = compiler.compile(longIntent, RULES);
        CanonicalExecutablePlan shortResult = compiler.compile(shortIntent, RULES);

        assertThat(first).isEqualTo(second);
        assertThat(first.entry().normalizedEntryPrice()).isEqualByComparingTo("100");
        assertThat(first.stopLoss().normalizedPrice()).isEqualByComparingTo("90");
        assertThat(first.takeProfitLegs().get(0).executableTargetPrice()).isEqualByComparingTo("110");
        assertThat(shortResult.status()).isEqualTo(CanonicalExecutablePlan.CompilationStatus.ACCEPTED);
        assertThat(shortResult.entry().normalizedEntryPrice()).isEqualByComparingTo("100.1");
        assertThat(shortResult.stopLoss().normalizedPrice()).isEqualByComparingTo("110.1");
        assertThat(shortResult.takeProfitLegs().get(0).executableTargetPrice()).isEqualByComparingTo("90.0");
    }

    @Test
    void rejectsLongStopRoundingThatExceedsApprovedRiskBudget() {
        TradeIntent intent = new TradeIntent(
                UUID.randomUUID(), "XAU-USDT-SWAP", TradePlanType.OPEN_LONG, "LIMIT",
                new BigDecimal("100"), new BigDecimal("90.06"), new BigDecimal("118"),
                new BigDecimal("10"), new BigDecimal("99"), "strategy-v1",
                List.of(
                        new TradeIntent.ExitLeg("TP1", BigDecimal.ONE, new BigDecimal("110"), new BigDecimal("0.30"), "first"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), new BigDecimal("120"), new BigDecimal("0.40"), "second"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("1.8"), new BigDecimal("118"), new BigDecimal("0.30"), "final")));

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(intent, RULES);

        assertThat(result.status()).isEqualTo(CanonicalExecutablePlan.CompilationStatus.REJECTED);
        assertThat(result.decisions()).contains("EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
    }

    @Test
    void rejectsShortStopRoundingThatExceedsApprovedRiskBudget() {
        TradeIntent intent = new TradeIntent(
                UUID.randomUUID(), "XAU-USDT-SWAP", TradePlanType.OPEN_SHORT, "LIMIT",
                new BigDecimal("100"), new BigDecimal("110.06"), new BigDecimal("82"),
                new BigDecimal("10"), new BigDecimal("100"), "strategy-v1",
                List.of(
                        new TradeIntent.ExitLeg("TP1", BigDecimal.ONE, new BigDecimal("90"), new BigDecimal("0.30"), "first"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), new BigDecimal("80"), new BigDecimal("0.40"), "second"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("1.8"), new BigDecimal("82"), new BigDecimal("0.30"), "final")));

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(intent, RULES);

        assertThat(result.status()).isEqualTo(CanonicalExecutablePlan.CompilationStatus.REJECTED);
        assertThat(result.decisions()).contains("EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
    }

    @ParameterizedTest
    @CsvSource({"99.9, 995", "0.4, 0"})
    void automaticSizingRespectsQuoteContractValueAndMinimumLot(String budget, int expectedContracts) {
        var rules = new OkxInstrumentRules("XAU-USDT-SWAP", BigDecimal.ONE, "USDT",
                BigDecimal.valueOf(5), BigDecimal.valueOf(5), new BigDecimal("0.1"));
        TradeIntent base = intent(BigDecimal.TEN, DirectionBias.BULLISH);
        TradeIntent sized = new TradeIntent(base.planId(), base.instId(), base.action(), base.entryPolicy(),
                new BigDecimal("100.04"), base.initialStopLossPrice(), base.intendedTakeProfitPrice(),
                base.intendedBaseQuantity(), new BigDecimal(budget), base.strategyVersion(), base.exitLegs());

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compileAutomaticEntry(sized, rules);

        if (expectedContracts == 0) {
            assertThat(result.accepted()).isFalse();
            assertThat(result.decisions()).containsExactly("EXECUTABLE_RISK_EXCEEDS_RISK_BUDGET");
        } else {
            assertThat(result.accepted()).isTrue();
            assertThat(result.entry().executableContractQuantity()).isEqualByComparingTo(String.valueOf(expectedContracts));
            assertThat(result.risk().executableStopLossAmount()).isEqualByComparingTo("99.5");
        }
    }

    @Test
    void scalesOnlyQuantitiesForPartialFillAndPreservesCanonicalTargets() {
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(
                intent(new BigDecimal("10"), DirectionBias.BULLISH), RULES);

        List<CanonicalExecutablePlan.ExitLeg> scaled = new CanonicalPlanCompiler()
                .scaleExitLegs(plan, new BigDecimal("7"), RULES);

        assertThat(scaled).extracting(CanonicalExecutablePlan.ExitLeg::executableContractQuantity)
                .containsExactly(new BigDecimal("2"), new BigDecimal("2"), new BigDecimal("3"));
        assertThat(scaled.get(0).executableTargetPrice()).isEqualByComparingTo("110");
        assertThat(scaled.get(1).executableTargetPrice()).isEqualByComparingTo("120");
        assertThat(scaled.get(2).executableTargetPrice()).isEqualByComparingTo("122");
    }

    @Test
    void persistedSnapshotRoundTripsWithoutRecompilingAgainstLaterRules() {
        CanonicalPlanCompiler compiler = new CanonicalPlanCompiler();
        CanonicalExecutablePlan plan = compiler.compile(intent(new BigDecimal("10"), DirectionBias.BULLISH), RULES);

        CanonicalExecutablePlan restored = CanonicalPlanJsonCodec.read(CanonicalPlanJsonCodec.write(plan));

        assertThat(restored).isEqualTo(plan);
        assertThat(restored.compilerVersion()).isEqualTo(CanonicalPlanCompiler.VERSION);
        assertThat(restored.instrument().tickSize()).isEqualByComparingTo("0.1");
    }

    @Test
    void persistedSnapshotCarriesSchemaCompilerAndCompiledAtMetadata() {
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(intent(new BigDecimal("10"), DirectionBias.BULLISH), RULES);

        String json = CanonicalPlanJsonCodec.write(plan);

        assertThat(json).contains("\"schemaVersion\":\"" + CanonicalPlanJsonCodec.SCHEMA_VERSION + "\"")
                .contains("\"compilerVersion\":\"" + CanonicalPlanCompiler.VERSION + "\"")
                .contains("\"compiledAt\":");
    }

    @Test
    void explicitCompiledAtIsStableAcrossPersistenceTargets() {
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(intent(new BigDecimal("10"), DirectionBias.BULLISH), RULES);
        java.time.Instant compiledAt = java.time.Instant.parse("2026-09-15T00:00:00Z");

        String first = CanonicalPlanJsonCodec.write(plan, compiledAt);
        String second = CanonicalPlanJsonCodec.write(plan, compiledAt);

        assertThat(first).isEqualTo(second).contains("\"compiledAt\":\"2026-09-15T00:00:00Z\"");
    }

    @Test
    void marketEntryKeepsReferencePriceRoleInsteadOfClaimingExecutableFillPrice() {
        TradeIntent market = new TradeIntent(
                UUID.randomUUID(), "XAU-USDT-SWAP", TradePlanType.OPEN_LONG, "MARKET",
                new BigDecimal("100"), new BigDecimal("90"), new BigDecimal("122"),
                new BigDecimal("10"), new BigDecimal("1000"), "strategy-v1",
                List.of(
                        new TradeIntent.ExitLeg("TP1", BigDecimal.ONE, new BigDecimal("110"), new BigDecimal("0.30"), "first"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), new BigDecimal("120"), new BigDecimal("0.40"), "second"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("2.2"), new BigDecimal("122"), new BigDecimal("0.30"), "final")));

        CanonicalExecutablePlan result = new CanonicalPlanCompiler().compile(market, RULES);

        assertThat(result.entry().priceRole()).isEqualTo("REFERENCE");
        assertThat(result.entry().isReferenceOnly()).isTrue();
    }

    @Test
    void unknownPersistedSchemaIsRejected() {
        CanonicalExecutablePlan plan = new CanonicalPlanCompiler().compile(intent(new BigDecimal("10"), DirectionBias.BULLISH), RULES);
        String json = CanonicalPlanJsonCodec.write(plan).replace(
                CanonicalPlanJsonCodec.SCHEMA_VERSION, "canonical-plan-schema-v99");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CanonicalPlanJsonCodec.read(json))
                .hasMessageContaining("未知 canonical plan schemaVersion");
    }

    private static TradeIntent intent(BigDecimal quantity, DirectionBias direction) {
        boolean bullish = direction == DirectionBias.BULLISH;
        return new TradeIntent(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "XAU-USDT-SWAP", bullish ? TradePlanType.OPEN_LONG : TradePlanType.OPEN_SHORT, "LIMIT",
                new BigDecimal("100"), bullish ? new BigDecimal("90") : new BigDecimal("110"),
                bullish ? new BigDecimal("120") : new BigDecimal("80"), quantity,
                new BigDecimal("1000"), "strategy-v1",
                List.of(
                        new TradeIntent.ExitLeg("TP1", new BigDecimal("1"), bullish ? new BigDecimal("110") : new BigDecimal("90"), new BigDecimal("0.30"), "first"),
                        new TradeIntent.ExitLeg("TP2", new BigDecimal("2"), bullish ? new BigDecimal("120") : new BigDecimal("80"), new BigDecimal("0.40"), "second"),
                        new TradeIntent.ExitLeg("TP3", new BigDecimal("2.2"), bullish ? new BigDecimal("122") : new BigDecimal("78"), new BigDecimal("0.30"), "final")
                ));
    }
}
