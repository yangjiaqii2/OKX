package com.example.quant.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "quant.agent")
public class AgentProperties {
    private boolean enabled = false;
    private String mode = "MANUAL_CONFIRM";
    private int scanIntervalSeconds = 300;
    private int riskBlockScanIntervalSeconds = 1800;
    private int topCandidateLimit = 10;
    private AutoTrade autoTrade = new AutoTrade();
    private Score score = new Score();
    private Market market = new Market();
    private Entry entry = new Entry();
    private TodayChange todayChange = new TodayChange();
    private Ranking ranking = new Ranking();
    private Budget budget = new Budget();
    private Fallback fallback = new Fallback();
    private PositionQuality positionQuality = new PositionQuality();
    private Exposure exposure = new Exposure();
    private PreConfirmRefresh preConfirmRefresh = new PreConfirmRefresh();
    private NewsRisk newsRisk = new NewsRisk();
    private Concurrency concurrency = new Concurrency();
    private Timeout timeout = new Timeout();
    private Idempotency idempotency = new Idempotency();
    private Retry retry = new Retry();
    private Recovery recovery = new Recovery();
    private Protection protection = new Protection();
    private TakeProfit takeProfit = new TakeProfit();
    private Risk risk = new Risk();
    private Monitor monitor = new Monitor();
    private Lifecycle lifecycle = new Lifecycle();

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String mode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public int scanIntervalSeconds() {
        return scanIntervalSeconds;
    }

    public void setScanIntervalSeconds(int scanIntervalSeconds) {
        this.scanIntervalSeconds = scanIntervalSeconds;
    }

    public int riskBlockScanIntervalSeconds() {
        return riskBlockScanIntervalSeconds;
    }

    public void setRiskBlockScanIntervalSeconds(int riskBlockScanIntervalSeconds) {
        this.riskBlockScanIntervalSeconds = riskBlockScanIntervalSeconds;
    }

    public int topCandidateLimit() {
        return topCandidateLimit;
    }

    public void setTopCandidateLimit(int topCandidateLimit) {
        this.topCandidateLimit = topCandidateLimit;
    }

    public AutoTrade autoTrade() {
        return autoTrade;
    }

    public void setAutoTrade(AutoTrade autoTrade) {
        this.autoTrade = autoTrade;
    }

    public Score score() {
        return score;
    }

    public void setScore(Score score) {
        this.score = score;
    }

    public Market market() {
        return market;
    }

    public void setMarket(Market market) {
        this.market = market;
    }

    public Entry entry() {
        return entry;
    }

    public void setEntry(Entry entry) {
        this.entry = entry;
    }

    public TodayChange todayChange() {
        return todayChange;
    }

    public void setTodayChange(TodayChange todayChange) {
        this.todayChange = todayChange;
    }

    public Ranking ranking() {
        return ranking;
    }

    public void setRanking(Ranking ranking) {
        this.ranking = ranking;
    }

    public Budget budget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public Fallback fallback() {
        return fallback;
    }

    public void setFallback(Fallback fallback) {
        this.fallback = fallback;
    }

    public PositionQuality positionQuality() {
        return positionQuality;
    }

    public void setPositionQuality(PositionQuality positionQuality) {
        this.positionQuality = positionQuality;
    }

    public Exposure exposure() {
        return exposure;
    }

    public void setExposure(Exposure exposure) {
        this.exposure = exposure;
    }

    public PreConfirmRefresh preConfirmRefresh() {
        return preConfirmRefresh;
    }

    public void setPreConfirmRefresh(PreConfirmRefresh preConfirmRefresh) {
        this.preConfirmRefresh = preConfirmRefresh;
    }

    public NewsRisk newsRisk() {
        return newsRisk;
    }

    public void setNewsRisk(NewsRisk newsRisk) {
        this.newsRisk = newsRisk == null ? new NewsRisk() : newsRisk;
    }

    public Concurrency concurrency() {
        return concurrency;
    }

    public void setConcurrency(Concurrency concurrency) {
        this.concurrency = concurrency;
    }

    public Timeout timeout() {
        return timeout;
    }

    public void setTimeout(Timeout timeout) {
        this.timeout = timeout;
    }

    public Idempotency idempotency() {
        return idempotency;
    }

    public void setIdempotency(Idempotency idempotency) {
        this.idempotency = idempotency;
    }

    public Retry retry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public Recovery recovery() {
        return recovery;
    }

    public void setRecovery(Recovery recovery) {
        this.recovery = recovery;
    }

    public Protection protection() {
        return protection;
    }

    public void setProtection(Protection protection) {
        this.protection = protection;
    }

    public TakeProfit takeProfit() {
        return takeProfit;
    }

    public void setTakeProfit(TakeProfit takeProfit) {
        this.takeProfit = takeProfit;
    }

    public Risk risk() {
        return risk;
    }

    public void setRisk(Risk risk) {
        this.risk = risk;
    }

    public Monitor monitor() {
        return monitor;
    }

    public void setMonitor(Monitor monitor) {
        this.monitor = monitor;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle == null ? new Lifecycle() : lifecycle;
    }

    public static class AutoTrade {
        private boolean enabled = true;
        private int minScore = 80;
        private BigDecimal minRiskReward = BigDecimal.valueOf(2);
        private boolean requireBacktestPass = false;
        private int noRiskMinScore = 70;
        private int maxOpenPositions = 3;
        private int minOpenPositions = 3;
        private int inFlightOrderHoldSeconds = 120;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int minScore() {
            return minScore;
        }

        public void setMinScore(int minScore) {
            this.minScore = minScore;
        }

        public BigDecimal minRiskReward() {
            return minRiskReward;
        }

        public void setMinRiskReward(BigDecimal minRiskReward) {
            this.minRiskReward = minRiskReward;
        }

        public boolean requireBacktestPass() {
            return requireBacktestPass;
        }

        public void setRequireBacktestPass(boolean requireBacktestPass) {
            this.requireBacktestPass = requireBacktestPass;
        }

        public int noRiskMinScore() {
            return noRiskMinScore;
        }

        public void setNoRiskMinScore(int noRiskMinScore) {
            this.noRiskMinScore = noRiskMinScore;
        }

        public int maxOpenPositions() {
            return maxOpenPositions;
        }

        public void setMaxOpenPositions(int maxOpenPositions) {
            this.maxOpenPositions = maxOpenPositions;
        }

        public int minOpenPositions() {
            return minOpenPositions;
        }

        public void setMinOpenPositions(int minOpenPositions) {
            this.minOpenPositions = minOpenPositions;
        }

        public int inFlightOrderHoldSeconds() {
            return inFlightOrderHoldSeconds;
        }

        public void setInFlightOrderHoldSeconds(int inFlightOrderHoldSeconds) {
            this.inFlightOrderHoldSeconds = inFlightOrderHoldSeconds;
        }
    }

    public static class Score {
        private int minTotalScore = 75;
        private int minTrendScore = 18;
        private int minVolumeScore = 17;
        private int minLiquidityScore = 10;
        private int minNewsRiskScore = 0;

        public int minTotalScore() {
            return minTotalScore;
        }

        public void setMinTotalScore(int minTotalScore) {
            this.minTotalScore = minTotalScore;
        }

        public int minTrendScore() {
            return minTrendScore;
        }

        public void setMinTrendScore(int minTrendScore) {
            this.minTrendScore = minTrendScore;
        }

        public int minVolumeScore() {
            return minVolumeScore;
        }

        public void setMinVolumeScore(int minVolumeScore) {
            this.minVolumeScore = minVolumeScore;
        }

        public int minLiquidityScore() {
            return minLiquidityScore;
        }

        public void setMinLiquidityScore(int minLiquidityScore) {
            this.minLiquidityScore = minLiquidityScore;
        }

        public int minNewsRiskScore() {
            return minNewsRiskScore;
        }

        public void setMinNewsRiskScore(int minNewsRiskScore) {
            this.minNewsRiskScore = minNewsRiskScore;
        }
    }

    public static class Market {
        private BigDecimal minVolume24hUsdt = BigDecimal.valueOf(50_000_000);
        private BigDecimal maxSpreadBps = BigDecimal.valueOf(8);
        private BigDecimal minDepthUsdt = BigDecimal.valueOf(100_000);
        private BigDecimal maxDepthUsageRate = new BigDecimal("0.20");
        private BigDecimal maxFundingAbs = new BigDecimal("0.0015");
        private BigDecimal maxWickRatio = new BigDecimal("0.55");
        private BigDecimal max24hRangePct = BigDecimal.valueOf(40);
        private BigDecimal maxEstimatedSlippageBps = BigDecimal.valueOf(15);
        private int staleDataSeconds = 20;

        public BigDecimal minVolume24hUsdt() {
            return minVolume24hUsdt;
        }

        public void setMinVolume24hUsdt(BigDecimal minVolume24hUsdt) {
            this.minVolume24hUsdt = minVolume24hUsdt;
        }

        public BigDecimal maxSpreadBps() {
            return maxSpreadBps;
        }

        public void setMaxSpreadBps(BigDecimal maxSpreadBps) {
            this.maxSpreadBps = maxSpreadBps;
        }

        public BigDecimal minDepthUsdt() {
            return minDepthUsdt;
        }

        public void setMinDepthUsdt(BigDecimal minDepthUsdt) {
            this.minDepthUsdt = minDepthUsdt;
        }

        public BigDecimal maxDepthUsageRate() {
            return maxDepthUsageRate;
        }

        public void setMaxDepthUsageRate(BigDecimal maxDepthUsageRate) {
            this.maxDepthUsageRate = maxDepthUsageRate;
        }

        public BigDecimal maxFundingAbs() {
            return maxFundingAbs;
        }

        public void setMaxFundingAbs(BigDecimal maxFundingAbs) {
            this.maxFundingAbs = maxFundingAbs;
        }

        public BigDecimal maxWickRatio() {
            return maxWickRatio;
        }

        public void setMaxWickRatio(BigDecimal maxWickRatio) {
            this.maxWickRatio = maxWickRatio;
        }

        public int staleDataSeconds() {
            return staleDataSeconds;
        }

        public void setStaleDataSeconds(int staleDataSeconds) {
            this.staleDataSeconds = staleDataSeconds;
        }

        public BigDecimal max24hRangePct() {
            return max24hRangePct;
        }

        public void setMax24hRangePct(BigDecimal max24hRangePct) {
            this.max24hRangePct = max24hRangePct;
        }

        public BigDecimal maxEstimatedSlippageBps() {
            return maxEstimatedSlippageBps;
        }

        public void setMaxEstimatedSlippageBps(BigDecimal maxEstimatedSlippageBps) {
            this.maxEstimatedSlippageBps = maxEstimatedSlippageBps;
        }
    }

    public static class Entry {
        private boolean smartEntryEnabled = true;
        private int marketMinScore = 85;
        private BigDecimal marketMinRiskReward = new BigDecimal("1.8");
        private BigDecimal maxMarketSpreadPct = new BigDecimal("0.03");
        private BigDecimal maxMarketSlippagePct = new BigDecimal("0.12");
        private BigDecimal maxDistanceFromEma20Pct = new BigDecimal("1.5");
        private List<String> allowMarketSignalTypes = new ArrayList<>(List.of("STRONG_LONG", "TREND_SHORT"));
        private List<String> waitPullbackSignalTypes = new ArrayList<>(List.of("PULLBACK_LONG"));
        private boolean reversalShortForceConfirm = true;

        public boolean smartEntryEnabled() {
            return smartEntryEnabled;
        }

        public void setSmartEntryEnabled(boolean smartEntryEnabled) {
            this.smartEntryEnabled = smartEntryEnabled;
        }

        public int marketMinScore() {
            return marketMinScore;
        }

        public void setMarketMinScore(int marketMinScore) {
            this.marketMinScore = marketMinScore;
        }

        public BigDecimal marketMinRiskReward() {
            return marketMinRiskReward;
        }

        public void setMarketMinRiskReward(BigDecimal marketMinRiskReward) {
            this.marketMinRiskReward = marketMinRiskReward;
        }

        public BigDecimal maxMarketSpreadPct() {
            return maxMarketSpreadPct;
        }

        public void setMaxMarketSpreadPct(BigDecimal maxMarketSpreadPct) {
            this.maxMarketSpreadPct = maxMarketSpreadPct;
        }

        public BigDecimal maxMarketSlippagePct() {
            return maxMarketSlippagePct;
        }

        public void setMaxMarketSlippagePct(BigDecimal maxMarketSlippagePct) {
            this.maxMarketSlippagePct = maxMarketSlippagePct;
        }

        public BigDecimal maxDistanceFromEma20Pct() {
            return maxDistanceFromEma20Pct;
        }

        public void setMaxDistanceFromEma20Pct(BigDecimal maxDistanceFromEma20Pct) {
            this.maxDistanceFromEma20Pct = maxDistanceFromEma20Pct;
        }

        public List<String> allowMarketSignalTypes() {
            return allowMarketSignalTypes;
        }

        public void setAllowMarketSignalTypes(List<String> allowMarketSignalTypes) {
            this.allowMarketSignalTypes = allowMarketSignalTypes == null ? new ArrayList<>() : new ArrayList<>(allowMarketSignalTypes);
        }

        public List<String> waitPullbackSignalTypes() {
            return waitPullbackSignalTypes;
        }

        public void setWaitPullbackSignalTypes(List<String> waitPullbackSignalTypes) {
            this.waitPullbackSignalTypes = waitPullbackSignalTypes == null ? new ArrayList<>() : new ArrayList<>(waitPullbackSignalTypes);
        }

        public boolean reversalShortForceConfirm() {
            return reversalShortForceConfirm;
        }

        public void setReversalShortForceConfirm(boolean reversalShortForceConfirm) {
            this.reversalShortForceConfirm = reversalShortForceConfirm;
        }
    }

    public static class TodayChange {
        private BigDecimal longHealthyMinPct = BigDecimal.valueOf(3);
        private BigDecimal longHealthyMaxPct = BigDecimal.valueOf(15);
        private BigDecimal longLightOverheatedMaxPct = BigDecimal.valueOf(25);
        private BigDecimal longHighOverheatedMaxPct = BigDecimal.valueOf(50);
        private BigDecimal longForbiddenPct = BigDecimal.valueOf(50);
        private BigDecimal neutralMinPct = BigDecimal.valueOf(-3);
        private BigDecimal neutralMaxPct = BigDecimal.valueOf(3);
        private BigDecimal shortWeakMinPct = BigDecimal.valueOf(-10);
        private BigDecimal shortWeakMaxPct = BigDecimal.valueOf(-3);
        private BigDecimal shortOversoldBelowPct = BigDecimal.valueOf(-10);

        public BigDecimal longHealthyMinPct() {
            return longHealthyMinPct;
        }

        public void setLongHealthyMinPct(BigDecimal longHealthyMinPct) {
            this.longHealthyMinPct = longHealthyMinPct;
        }

        public BigDecimal longHealthyMaxPct() {
            return longHealthyMaxPct;
        }

        public void setLongHealthyMaxPct(BigDecimal longHealthyMaxPct) {
            this.longHealthyMaxPct = longHealthyMaxPct;
        }

        public BigDecimal longLightOverheatedMaxPct() {
            return longLightOverheatedMaxPct;
        }

        public void setLongLightOverheatedMaxPct(BigDecimal longLightOverheatedMaxPct) {
            this.longLightOverheatedMaxPct = longLightOverheatedMaxPct;
        }

        public BigDecimal longHighOverheatedMaxPct() {
            return longHighOverheatedMaxPct;
        }

        public void setLongHighOverheatedMaxPct(BigDecimal longHighOverheatedMaxPct) {
            this.longHighOverheatedMaxPct = longHighOverheatedMaxPct;
        }

        public BigDecimal longForbiddenPct() {
            return longForbiddenPct;
        }

        public void setLongForbiddenPct(BigDecimal longForbiddenPct) {
            this.longForbiddenPct = longForbiddenPct;
        }

        public BigDecimal neutralMinPct() {
            return neutralMinPct;
        }

        public void setNeutralMinPct(BigDecimal neutralMinPct) {
            this.neutralMinPct = neutralMinPct;
        }

        public BigDecimal neutralMaxPct() {
            return neutralMaxPct;
        }

        public void setNeutralMaxPct(BigDecimal neutralMaxPct) {
            this.neutralMaxPct = neutralMaxPct;
        }

        public BigDecimal shortWeakMinPct() {
            return shortWeakMinPct;
        }

        public void setShortWeakMinPct(BigDecimal shortWeakMinPct) {
            this.shortWeakMinPct = shortWeakMinPct;
        }

        public BigDecimal shortWeakMaxPct() {
            return shortWeakMaxPct;
        }

        public void setShortWeakMaxPct(BigDecimal shortWeakMaxPct) {
            this.shortWeakMaxPct = shortWeakMaxPct;
        }

        public BigDecimal shortOversoldBelowPct() {
            return shortOversoldBelowPct;
        }

        public void setShortOversoldBelowPct(BigDecimal shortOversoldBelowPct) {
            this.shortOversoldBelowPct = shortOversoldBelowPct;
        }
    }

    public static class Ranking {
        private int maxAutoTradeFallbackRank = 5;
        private BigDecimal minRiskReward = BigDecimal.valueOf(1.5);
        private int minFinalRankScore = 70;

        public int maxAutoTradeFallbackRank() {
            return maxAutoTradeFallbackRank;
        }

        public void setMaxAutoTradeFallbackRank(int maxAutoTradeFallbackRank) {
            this.maxAutoTradeFallbackRank = maxAutoTradeFallbackRank;
        }

        public BigDecimal minRiskReward() {
            return minRiskReward;
        }

        public void setMinRiskReward(BigDecimal minRiskReward) {
            this.minRiskReward = minRiskReward;
        }

        public int minFinalRankScore() {
            return minFinalRankScore;
        }

        public void setMinFinalRankScore(int minFinalRankScore) {
            this.minFinalRankScore = minFinalRankScore;
        }
    }

    public static class Budget {
        private boolean enabled = true;
        private boolean inputMarginMeansTotalBudget = true;
        private String allocationMode = "TARGET_UTILIZATION";
        private BigDecimal targetUtilizationPct = BigDecimal.valueOf(90);
        private BigDecimal minTargetUtilizationPct = BigDecimal.valueOf(80);
        private BigDecimal maxUtilizationPct = BigDecimal.valueOf(100);
        private BigDecimal minOrderMarginUsdt = BigDecimal.valueOf(5);
        private BigDecimal preferredMinOrderMarginUsdt = BigDecimal.valueOf(10);
        private BigDecimal maxSinglePositionBudgetPct = BigDecimal.valueOf(50);
        private Map<Integer, BigDecimal> slotWeights = new LinkedHashMap<>(
                Map.of(1, new BigDecimal("0.45"), 2, new BigDecimal("0.30"), 3, new BigDecimal("0.25"))
        );
        private Map<String, BigDecimal> scoreMarginFactor = new LinkedHashMap<>(
                Map.of("score-90-plus", new BigDecimal("1.00"),
                        "score-85-90", new BigDecimal("0.90"),
                        "score-80-85", new BigDecimal("0.80"),
                        "score-75-80", new BigDecimal("0.60"),
                        "score-70-75", new BigDecimal("0.60"),
                        "score-60-70", new BigDecimal("0.60"))
        );
        private boolean allowRedistributeUnusedBudget = true;
        private int redistributeOnlyToScoreAbove = 85;
        private boolean reserveInflightBudget = true;
        private boolean includeManualPositions = false;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean inputMarginMeansTotalBudget() {
            return inputMarginMeansTotalBudget;
        }

        public void setInputMarginMeansTotalBudget(boolean inputMarginMeansTotalBudget) {
            this.inputMarginMeansTotalBudget = inputMarginMeansTotalBudget;
        }

        public String allocationMode() {
            return allocationMode;
        }

        public void setAllocationMode(String allocationMode) {
            this.allocationMode = allocationMode;
        }

        public BigDecimal targetUtilizationPct() {
            return targetUtilizationPct;
        }

        public void setTargetUtilizationPct(BigDecimal targetUtilizationPct) {
            this.targetUtilizationPct = targetUtilizationPct;
        }

        public BigDecimal minTargetUtilizationPct() {
            return minTargetUtilizationPct;
        }

        public void setMinTargetUtilizationPct(BigDecimal minTargetUtilizationPct) {
            this.minTargetUtilizationPct = minTargetUtilizationPct;
        }

        public BigDecimal maxUtilizationPct() {
            return maxUtilizationPct;
        }

        public void setMaxUtilizationPct(BigDecimal maxUtilizationPct) {
            this.maxUtilizationPct = maxUtilizationPct;
        }

        public BigDecimal minOrderMarginUsdt() {
            return minOrderMarginUsdt;
        }

        public void setMinOrderMarginUsdt(BigDecimal minOrderMarginUsdt) {
            this.minOrderMarginUsdt = minOrderMarginUsdt;
        }

        public BigDecimal preferredMinOrderMarginUsdt() {
            return preferredMinOrderMarginUsdt;
        }

        public void setPreferredMinOrderMarginUsdt(BigDecimal preferredMinOrderMarginUsdt) {
            this.preferredMinOrderMarginUsdt = preferredMinOrderMarginUsdt;
        }

        public BigDecimal maxSinglePositionBudgetPct() {
            return maxSinglePositionBudgetPct;
        }

        public void setMaxSinglePositionBudgetPct(BigDecimal maxSinglePositionBudgetPct) {
            this.maxSinglePositionBudgetPct = maxSinglePositionBudgetPct;
        }

        public Map<Integer, BigDecimal> slotWeights() {
            return slotWeights;
        }

        public void setSlotWeights(Map<Integer, BigDecimal> slotWeights) {
            this.slotWeights = slotWeights == null ? new LinkedHashMap<>() : new LinkedHashMap<>(slotWeights);
        }

        public Map<String, BigDecimal> scoreMarginFactor() {
            return scoreMarginFactor;
        }

        public void setScoreMarginFactor(Map<String, BigDecimal> scoreMarginFactor) {
            this.scoreMarginFactor = scoreMarginFactor == null ? new LinkedHashMap<>() : new LinkedHashMap<>(scoreMarginFactor);
        }

        public boolean allowRedistributeUnusedBudget() {
            return allowRedistributeUnusedBudget;
        }

        public void setAllowRedistributeUnusedBudget(boolean allowRedistributeUnusedBudget) {
            this.allowRedistributeUnusedBudget = allowRedistributeUnusedBudget;
        }

        public int redistributeOnlyToScoreAbove() {
            return redistributeOnlyToScoreAbove;
        }

        public void setRedistributeOnlyToScoreAbove(int redistributeOnlyToScoreAbove) {
            this.redistributeOnlyToScoreAbove = redistributeOnlyToScoreAbove;
        }

        public boolean reserveInflightBudget() {
            return reserveInflightBudget;
        }

        public void setReserveInflightBudget(boolean reserveInflightBudget) {
            this.reserveInflightBudget = reserveInflightBudget;
        }

        public boolean includeManualPositions() {
            return includeManualPositions;
        }

        public void setIncludeManualPositions(boolean includeManualPositions) {
            this.includeManualPositions = includeManualPositions;
        }

        public BigDecimal slotWeight(int slotIndex) {
            return slotWeights.getOrDefault(slotIndex, BigDecimal.ZERO);
        }

        public BigDecimal scoreFactor(BigDecimal score) {
            if (score == null) {
                return BigDecimal.ZERO;
            }
            if (score.compareTo(BigDecimal.valueOf(90)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-90-plus", BigDecimal.ONE);
            }
            if (score.compareTo(BigDecimal.valueOf(85)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-85-90", new BigDecimal("0.90"));
            }
            if (score.compareTo(BigDecimal.valueOf(80)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-80-85", new BigDecimal("0.80"));
            }
            if (score.compareTo(BigDecimal.valueOf(75)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-75-80", new BigDecimal("0.60"));
            }
            if (score.compareTo(BigDecimal.valueOf(70)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-70-75", new BigDecimal("0.60"));
            }
            if (score.compareTo(BigDecimal.valueOf(60)) >= 0) {
                return scoreMarginFactor.getOrDefault("score-60-70", new BigDecimal("0.60"));
            }
            return BigDecimal.ZERO;
        }
    }

    public static class Fallback {
        private boolean continueOnPlanRiskFail = true;
        private boolean continueOnSpreadFail = true;
        private boolean continueOnSlippageFail = true;
        private boolean continueOnAiPlanInvalid = true;
        private boolean stopOnOkxApiError = true;
        private boolean stopOnAccountRiskError = true;
        private boolean stopOnProtectionFail = true;

        public boolean continueOnPlanRiskFail() {
            return continueOnPlanRiskFail;
        }

        public void setContinueOnPlanRiskFail(boolean continueOnPlanRiskFail) {
            this.continueOnPlanRiskFail = continueOnPlanRiskFail;
        }

        public boolean continueOnSpreadFail() {
            return continueOnSpreadFail;
        }

        public void setContinueOnSpreadFail(boolean continueOnSpreadFail) {
            this.continueOnSpreadFail = continueOnSpreadFail;
        }

        public boolean continueOnSlippageFail() {
            return continueOnSlippageFail;
        }

        public void setContinueOnSlippageFail(boolean continueOnSlippageFail) {
            this.continueOnSlippageFail = continueOnSlippageFail;
        }

        public boolean continueOnAiPlanInvalid() {
            return continueOnAiPlanInvalid;
        }

        public void setContinueOnAiPlanInvalid(boolean continueOnAiPlanInvalid) {
            this.continueOnAiPlanInvalid = continueOnAiPlanInvalid;
        }

        public boolean stopOnOkxApiError() {
            return stopOnOkxApiError;
        }

        public void setStopOnOkxApiError(boolean stopOnOkxApiError) {
            this.stopOnOkxApiError = stopOnOkxApiError;
        }

        public boolean stopOnAccountRiskError() {
            return stopOnAccountRiskError;
        }

        public void setStopOnAccountRiskError(boolean stopOnAccountRiskError) {
            this.stopOnAccountRiskError = stopOnAccountRiskError;
        }

        public boolean stopOnProtectionFail() {
            return stopOnProtectionFail;
        }

        public void setStopOnProtectionFail(boolean stopOnProtectionFail) {
            this.stopOnProtectionFail = stopOnProtectionFail;
        }
    }

    public static class PositionQuality {
        private boolean enabled = true;
        private Map<Integer, Integer> minScoreByOpenPositions = new LinkedHashMap<>(
                Map.of(0, 80, 1, 84, 2, 88)
        );
        private Map<Integer, Integer> minFinalRankScoreByOpenPositions = new LinkedHashMap<>(
                Map.of(0, 80, 1, 84, 2, 88)
        );
        private Map<String, Integer> maxPositionsByMarketRegime = new LinkedHashMap<>(
                Map.of("BULLISH", 3, "BEARISH", 3, "CHOPPY", 1, "HIGH_VOLATILITY", 1, "RISK_OFF", 0)
        );

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Map<Integer, Integer> minScoreByOpenPositions() {
            return minScoreByOpenPositions;
        }

        public void setMinScoreByOpenPositions(Map<Integer, Integer> minScoreByOpenPositions) {
            this.minScoreByOpenPositions = minScoreByOpenPositions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(minScoreByOpenPositions);
        }

        public Map<Integer, Integer> minFinalRankScoreByOpenPositions() {
            return minFinalRankScoreByOpenPositions;
        }

        public void setMinFinalRankScoreByOpenPositions(Map<Integer, Integer> minFinalRankScoreByOpenPositions) {
            this.minFinalRankScoreByOpenPositions = minFinalRankScoreByOpenPositions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(minFinalRankScoreByOpenPositions);
        }

        public Map<String, Integer> maxPositionsByMarketRegime() {
            return maxPositionsByMarketRegime;
        }

        public void setMaxPositionsByMarketRegime(Map<String, Integer> maxPositionsByMarketRegime) {
            this.maxPositionsByMarketRegime = maxPositionsByMarketRegime == null ? new LinkedHashMap<>() : new LinkedHashMap<>(maxPositionsByMarketRegime);
        }

        public int minScoreForOpenPositions(int openPositions) {
            return minScoreByOpenPositions.getOrDefault(openPositions,
                    minScoreByOpenPositions.getOrDefault(2, 88));
        }

        public int minFinalRankScoreForOpenPositions(int openPositions) {
            return minFinalRankScoreByOpenPositions.getOrDefault(openPositions,
                    minFinalRankScoreByOpenPositions.getOrDefault(2, 88));
        }

        public int maxPositionsForRegime(String regime) {
            return maxPositionsByMarketRegime.getOrDefault(regime, 1);
        }
    }

    public static class Exposure {
        private boolean enabled = true;
        private int maxSameDirectionAltPositions = 2;
        private int maxSameDirectionAltPositionsHighVolatility = 1;
        private int scorePenaltyPerSameDirectionPosition = 4;
        private List<String> excludeMajorsFromAltCount = new ArrayList<>(List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP"));

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int maxSameDirectionAltPositions() {
            return maxSameDirectionAltPositions;
        }

        public void setMaxSameDirectionAltPositions(int maxSameDirectionAltPositions) {
            this.maxSameDirectionAltPositions = maxSameDirectionAltPositions;
        }

        public int maxSameDirectionAltPositionsHighVolatility() {
            return maxSameDirectionAltPositionsHighVolatility;
        }

        public void setMaxSameDirectionAltPositionsHighVolatility(int maxSameDirectionAltPositionsHighVolatility) {
            this.maxSameDirectionAltPositionsHighVolatility = maxSameDirectionAltPositionsHighVolatility;
        }

        public int scorePenaltyPerSameDirectionPosition() {
            return scorePenaltyPerSameDirectionPosition;
        }

        public void setScorePenaltyPerSameDirectionPosition(int scorePenaltyPerSameDirectionPosition) {
            this.scorePenaltyPerSameDirectionPosition = scorePenaltyPerSameDirectionPosition;
        }

        public List<String> excludeMajorsFromAltCount() {
            return excludeMajorsFromAltCount;
        }

        public void setExcludeMajorsFromAltCount(List<String> excludeMajorsFromAltCount) {
            this.excludeMajorsFromAltCount = excludeMajorsFromAltCount == null ? new ArrayList<>() : new ArrayList<>(excludeMajorsFromAltCount);
        }
    }

    public static class PreConfirmRefresh {
        private boolean enabled = true;
        private BigDecimal maxPriceDeviationPct = new BigDecimal("0.25");
        private BigDecimal maxSpreadPct = new BigDecimal("0.05");
        private boolean rejectIf5mReverse = true;
        private boolean rejectIfBtcReverse = true;
        private boolean rejectIfNewsRiskUpgraded = true;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BigDecimal maxPriceDeviationPct() {
            return maxPriceDeviationPct;
        }

        public void setMaxPriceDeviationPct(BigDecimal maxPriceDeviationPct) {
            this.maxPriceDeviationPct = maxPriceDeviationPct;
        }

        public BigDecimal maxSpreadPct() {
            return maxSpreadPct;
        }

        public void setMaxSpreadPct(BigDecimal maxSpreadPct) {
            this.maxSpreadPct = maxSpreadPct;
        }

        public boolean rejectIf5mReverse() {
            return rejectIf5mReverse;
        }

        public void setRejectIf5mReverse(boolean rejectIf5mReverse) {
            this.rejectIf5mReverse = rejectIf5mReverse;
        }

        public boolean rejectIfBtcReverse() {
            return rejectIfBtcReverse;
        }

        public void setRejectIfBtcReverse(boolean rejectIfBtcReverse) {
            this.rejectIfBtcReverse = rejectIfBtcReverse;
        }

        public boolean rejectIfNewsRiskUpgraded() {
            return rejectIfNewsRiskUpgraded;
        }

        public void setRejectIfNewsRiskUpgraded(boolean rejectIfNewsRiskUpgraded) {
            this.rejectIfNewsRiskUpgraded = rejectIfNewsRiskUpgraded;
        }
    }

    public static class NewsRisk {
        private int missingDataScore = 20;

        public int missingDataScore() {
            return missingDataScore;
        }

        public void setMissingDataScore(int missingDataScore) {
            this.missingDataScore = Math.max(0, Math.min(100, missingDataScore));
        }
    }

    public static class Concurrency {
        private boolean scanLockEnabled = true;
        private boolean autoTradeLockEnabled = true;
        private boolean symbolLockEnabled = true;
        private int symbolLockTtlSeconds = 180;

        public boolean scanLockEnabled() {
            return scanLockEnabled;
        }

        public void setScanLockEnabled(boolean scanLockEnabled) {
            this.scanLockEnabled = scanLockEnabled;
        }

        public boolean autoTradeLockEnabled() {
            return autoTradeLockEnabled;
        }

        public void setAutoTradeLockEnabled(boolean autoTradeLockEnabled) {
            this.autoTradeLockEnabled = autoTradeLockEnabled;
        }

        public boolean symbolLockEnabled() {
            return symbolLockEnabled;
        }

        public void setSymbolLockEnabled(boolean symbolLockEnabled) {
            this.symbolLockEnabled = symbolLockEnabled;
        }

        public int symbolLockTtlSeconds() {
            return symbolLockTtlSeconds;
        }

        public void setSymbolLockTtlSeconds(int symbolLockTtlSeconds) {
            this.symbolLockTtlSeconds = symbolLockTtlSeconds;
        }
    }

    public static class Timeout {
        private int scanRoundTimeoutSeconds = 45;
        private int aiPlanTimeoutSeconds = 12;
        private int marketDataTimeoutSeconds = 8;
        private int okxOrderSubmitTimeoutSeconds = 8;
        private int okxOrderQueryTimeoutSeconds = 6;
        private int protectionSubmitTimeoutSeconds = 10;
        private int pendingOrderExpireSeconds = 120;

        public int scanRoundTimeoutSeconds() {
            return scanRoundTimeoutSeconds;
        }

        public void setScanRoundTimeoutSeconds(int scanRoundTimeoutSeconds) {
            this.scanRoundTimeoutSeconds = scanRoundTimeoutSeconds;
        }

        public int aiPlanTimeoutSeconds() {
            return aiPlanTimeoutSeconds;
        }

        public void setAiPlanTimeoutSeconds(int aiPlanTimeoutSeconds) {
            this.aiPlanTimeoutSeconds = aiPlanTimeoutSeconds;
        }

        public int marketDataTimeoutSeconds() {
            return marketDataTimeoutSeconds;
        }

        public void setMarketDataTimeoutSeconds(int marketDataTimeoutSeconds) {
            this.marketDataTimeoutSeconds = marketDataTimeoutSeconds;
        }

        public int okxOrderSubmitTimeoutSeconds() {
            return okxOrderSubmitTimeoutSeconds;
        }

        public void setOkxOrderSubmitTimeoutSeconds(int okxOrderSubmitTimeoutSeconds) {
            this.okxOrderSubmitTimeoutSeconds = okxOrderSubmitTimeoutSeconds;
        }

        public int okxOrderQueryTimeoutSeconds() {
            return okxOrderQueryTimeoutSeconds;
        }

        public void setOkxOrderQueryTimeoutSeconds(int okxOrderQueryTimeoutSeconds) {
            this.okxOrderQueryTimeoutSeconds = okxOrderQueryTimeoutSeconds;
        }

        public int protectionSubmitTimeoutSeconds() {
            return protectionSubmitTimeoutSeconds;
        }

        public void setProtectionSubmitTimeoutSeconds(int protectionSubmitTimeoutSeconds) {
            this.protectionSubmitTimeoutSeconds = protectionSubmitTimeoutSeconds;
        }

        public int pendingOrderExpireSeconds() {
            return pendingOrderExpireSeconds;
        }

        public void setPendingOrderExpireSeconds(int pendingOrderExpireSeconds) {
            this.pendingOrderExpireSeconds = pendingOrderExpireSeconds;
        }
    }

    public static class Idempotency {
        private boolean enabled = true;
        private String clientOrderIdPrefix = "AUTO";
        private boolean checkBeforeRetry = true;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String clientOrderIdPrefix() {
            return clientOrderIdPrefix;
        }

        public void setClientOrderIdPrefix(String clientOrderIdPrefix) {
            this.clientOrderIdPrefix = clientOrderIdPrefix;
        }

        public boolean checkBeforeRetry() {
            return checkBeforeRetry;
        }

        public void setCheckBeforeRetry(boolean checkBeforeRetry) {
            this.checkBeforeRetry = checkBeforeRetry;
        }
    }

    public static class Retry {
        private int okxSubmitRetry = 1;
        private int okxQueryRetry = 3;
        private int retryBackoffMillis = 500;

        public int okxSubmitRetry() {
            return okxSubmitRetry;
        }

        public void setOkxSubmitRetry(int okxSubmitRetry) {
            this.okxSubmitRetry = okxSubmitRetry;
        }

        public int okxQueryRetry() {
            return okxQueryRetry;
        }

        public void setOkxQueryRetry(int okxQueryRetry) {
            this.okxQueryRetry = okxQueryRetry;
        }

        public int retryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(int retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }
    }

    public static class Recovery {
        private boolean enabled = true;
        private int intervalSeconds = 30;
        private int reservedBudgetTimeoutSeconds = 180;
        private int submittedOrderTimeoutSeconds = 60;
        private int protectionPendingTimeoutSeconds = 20;
        private int unknownSubmitStatusTimeoutSeconds = 60;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int intervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int reservedBudgetTimeoutSeconds() {
            return reservedBudgetTimeoutSeconds;
        }

        public void setReservedBudgetTimeoutSeconds(int reservedBudgetTimeoutSeconds) {
            this.reservedBudgetTimeoutSeconds = reservedBudgetTimeoutSeconds;
        }

        public int submittedOrderTimeoutSeconds() {
            return submittedOrderTimeoutSeconds;
        }

        public void setSubmittedOrderTimeoutSeconds(int submittedOrderTimeoutSeconds) {
            this.submittedOrderTimeoutSeconds = submittedOrderTimeoutSeconds;
        }

        public int protectionPendingTimeoutSeconds() {
            return protectionPendingTimeoutSeconds;
        }

        public void setProtectionPendingTimeoutSeconds(int protectionPendingTimeoutSeconds) {
            this.protectionPendingTimeoutSeconds = protectionPendingTimeoutSeconds;
        }

        public int unknownSubmitStatusTimeoutSeconds() {
            return unknownSubmitStatusTimeoutSeconds;
        }

        public void setUnknownSubmitStatusTimeoutSeconds(int unknownSubmitStatusTimeoutSeconds) {
            this.unknownSubmitStatusTimeoutSeconds = unknownSubmitStatusTimeoutSeconds;
        }
    }

    public static class Protection {
        private boolean recalcAfterFill = true;
        private BigDecimal maxEntryPriceDeviationPct = new BigDecimal("0.25");
        private BigDecimal minRiskRewardAfterFill = new BigDecimal("1.5");
        private BigDecimal maxStopLossPctAfterFill = BigDecimal.valueOf(5);
        private int protectionSubmitRetry = 3;
        private String protectionFailAction = "CLOSE_POSITION";

        public boolean recalcAfterFill() {
            return recalcAfterFill;
        }

        public void setRecalcAfterFill(boolean recalcAfterFill) {
            this.recalcAfterFill = recalcAfterFill;
        }

        public BigDecimal maxEntryPriceDeviationPct() {
            return maxEntryPriceDeviationPct;
        }

        public void setMaxEntryPriceDeviationPct(BigDecimal maxEntryPriceDeviationPct) {
            this.maxEntryPriceDeviationPct = maxEntryPriceDeviationPct;
        }

        public BigDecimal minRiskRewardAfterFill() {
            return minRiskRewardAfterFill;
        }

        public void setMinRiskRewardAfterFill(BigDecimal minRiskRewardAfterFill) {
            this.minRiskRewardAfterFill = minRiskRewardAfterFill;
        }

        public BigDecimal maxStopLossPctAfterFill() {
            return maxStopLossPctAfterFill;
        }

        public void setMaxStopLossPctAfterFill(BigDecimal maxStopLossPctAfterFill) {
            this.maxStopLossPctAfterFill = maxStopLossPctAfterFill;
        }

        public int protectionSubmitRetry() {
            return protectionSubmitRetry;
        }

        public void setProtectionSubmitRetry(int protectionSubmitRetry) {
            this.protectionSubmitRetry = protectionSubmitRetry;
        }

        public String protectionFailAction() {
            return protectionFailAction;
        }

        public void setProtectionFailAction(String protectionFailAction) {
            this.protectionFailAction = protectionFailAction;
        }
    }

    public static class TakeProfit {
        private BigDecimal tp1Ratio = new BigDecimal("0.30");
        private BigDecimal tp2Ratio = new BigDecimal("0.40");
        private BigDecimal tp3Ratio = new BigDecimal("0.30");
        private boolean mergeSmallTpOrders = true;
        private boolean finalTpUseRemainingSize = true;

        public BigDecimal tp1Ratio() {
            return tp1Ratio;
        }

        public void setTp1Ratio(BigDecimal tp1Ratio) {
            this.tp1Ratio = tp1Ratio;
        }

        public BigDecimal tp2Ratio() {
            return tp2Ratio;
        }

        public void setTp2Ratio(BigDecimal tp2Ratio) {
            this.tp2Ratio = tp2Ratio;
        }

        public BigDecimal tp3Ratio() {
            return tp3Ratio;
        }

        public void setTp3Ratio(BigDecimal tp3Ratio) {
            this.tp3Ratio = tp3Ratio;
        }

        public boolean mergeSmallTpOrders() {
            return mergeSmallTpOrders;
        }

        public void setMergeSmallTpOrders(boolean mergeSmallTpOrders) {
            this.mergeSmallTpOrders = mergeSmallTpOrders;
        }

        public boolean finalTpUseRemainingSize() {
            return finalTpUseRemainingSize;
        }

        public void setFinalTpUseRemainingSize(boolean finalTpUseRemainingSize) {
            this.finalTpUseRemainingSize = finalTpUseRemainingSize;
        }
    }

    public static class Risk {
        private BigDecimal singleTradeRiskPercent = BigDecimal.ONE;
        private BigDecimal maxSingleLossPercent = BigDecimal.valueOf(5);
        private BigDecimal maxDailyLossPercent = BigDecimal.valueOf(30);
        private int maxDailyTrades = 5;
        private int maxConsecutiveLosses = 3;
        private int cooldownMinutesAfterLoss = 30;
        private int maxLeverage = 5;

        public BigDecimal singleTradeRiskPercent() {
            return singleTradeRiskPercent;
        }

        public void setSingleTradeRiskPercent(BigDecimal singleTradeRiskPercent) {
            this.singleTradeRiskPercent = singleTradeRiskPercent;
        }

        public BigDecimal maxSingleLossPercent() {
            return maxSingleLossPercent;
        }

        public void setMaxSingleLossPercent(BigDecimal maxSingleLossPercent) {
            this.maxSingleLossPercent = maxSingleLossPercent;
        }

        public BigDecimal maxDailyLossPercent() {
            return maxDailyLossPercent;
        }

        public void setMaxDailyLossPercent(BigDecimal maxDailyLossPercent) {
            this.maxDailyLossPercent = maxDailyLossPercent;
        }

        public int maxDailyTrades() {
            return maxDailyTrades;
        }

        public void setMaxDailyTrades(int maxDailyTrades) {
            this.maxDailyTrades = maxDailyTrades;
        }

        public int maxConsecutiveLosses() {
            return maxConsecutiveLosses;
        }

        public void setMaxConsecutiveLosses(int maxConsecutiveLosses) {
            this.maxConsecutiveLosses = maxConsecutiveLosses;
        }

        public int cooldownMinutesAfterLoss() {
            return cooldownMinutesAfterLoss;
        }

        public void setCooldownMinutesAfterLoss(int cooldownMinutesAfterLoss) {
            this.cooldownMinutesAfterLoss = cooldownMinutesAfterLoss;
        }

        public int maxLeverage() {
            return maxLeverage;
        }

        public void setMaxLeverage(int maxLeverage) {
            this.maxLeverage = maxLeverage;
        }
    }

    public static class Monitor {
        private int intervalSeconds = 10;
        private int maxHoldMinutes = 120;

        public int intervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }

        public int maxHoldMinutes() {
            return maxHoldMinutes;
        }

        public void setMaxHoldMinutes(int maxHoldMinutes) {
            this.maxHoldMinutes = maxHoldMinutes;
        }
    }

    public static class Lifecycle {
        private int entryTimeoutMinutes = 10;
        private int sidewaysPositionHours = 3;
        private BigDecimal sidewaysPnlRangePct = BigDecimal.ONE;
        private BigDecimal sidewaysExitProfitPct = new BigDecimal("0.3");
        private int maxHoldHours = 8;
        private String maxHoldAction = "CLOSE_POSITION";
        private String sidewaysAction = "TIGHTEN_TAKE_PROFIT";
        private boolean moveStopToBreakeven = false;

        public int entryTimeoutMinutes() {
            return entryTimeoutMinutes;
        }

        public void setEntryTimeoutMinutes(int entryTimeoutMinutes) {
            this.entryTimeoutMinutes = entryTimeoutMinutes;
        }

        public int sidewaysPositionHours() {
            return sidewaysPositionHours;
        }

        public void setSidewaysPositionHours(int sidewaysPositionHours) {
            this.sidewaysPositionHours = sidewaysPositionHours;
        }

        public BigDecimal sidewaysPnlRangePct() {
            return sidewaysPnlRangePct;
        }

        public void setSidewaysPnlRangePct(BigDecimal sidewaysPnlRangePct) {
            this.sidewaysPnlRangePct = sidewaysPnlRangePct;
        }

        public BigDecimal sidewaysExitProfitPct() {
            return sidewaysExitProfitPct;
        }

        public void setSidewaysExitProfitPct(BigDecimal sidewaysExitProfitPct) {
            this.sidewaysExitProfitPct = sidewaysExitProfitPct;
        }

        public int maxHoldHours() {
            return maxHoldHours;
        }

        public void setMaxHoldHours(int maxHoldHours) {
            this.maxHoldHours = maxHoldHours;
        }

        public String maxHoldAction() {
            return maxHoldAction;
        }

        public void setMaxHoldAction(String maxHoldAction) {
            this.maxHoldAction = maxHoldAction;
        }

        public String sidewaysAction() {
            return sidewaysAction;
        }

        public void setSidewaysAction(String sidewaysAction) {
            this.sidewaysAction = sidewaysAction;
        }

        public boolean moveStopToBreakeven() {
            return moveStopToBreakeven;
        }

        public void setMoveStopToBreakeven(boolean moveStopToBreakeven) {
            this.moveStopToBreakeven = moveStopToBreakeven;
        }
    }
}
