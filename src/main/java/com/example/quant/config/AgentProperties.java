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
    private AiSelection aiSelection = new AiSelection();
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
    private ProfitProtection profitProtection = new ProfitProtection();
    private Selection selection = new Selection();

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

    /** 深度分析上限；未配置时默认 {@code topCandidateLimit * 3}。 */
    public int deepAnalysisLimit() {
        Integer configured = selection().deepAnalysisLimit();
        if (configured != null && configured > 0) {
            return configured;
        }
        return Math.max(1, topCandidateLimit * 3);
    }

    public AiSelection aiSelection() {
        return aiSelection;
    }

    public void setAiSelection(AiSelection aiSelection) {
        this.aiSelection = aiSelection == null ? new AiSelection() : aiSelection;
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

    public ProfitProtection profitProtection() {
        return profitProtection;
    }

    public void setProfitProtection(ProfitProtection profitProtection) {
        this.profitProtection = profitProtection == null ? new ProfitProtection() : profitProtection;
    }

    public Selection selection() {
        return selection;
    }

    public void setSelection(Selection selection) {
        this.selection = selection == null ? new Selection() : selection;
    }

    public static class AutoTrade {
        private boolean enabled = true;
        private int minScore = 60;
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
        public static final int DEFAULT_MIN_TOTAL_SCORE = 60;

        private int minTotalScore = DEFAULT_MIN_TOTAL_SCORE;
        private int minTrendScore = 15;
        private int minVolumeScore = 15;
        private int minLiquidityScore = 10;
        /** @deprecated weighted 0-5 scale; configure minNewsRiskRawScore instead. */
        private Integer minNewsRiskScore;
        private int minNewsRiskRawScore = 70;

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
            return minNewsRiskScore == null ? 0 : minNewsRiskScore;
        }

        public void setMinNewsRiskScore(int minNewsRiskScore) {
            this.minNewsRiskScore = minNewsRiskScore;
        }

        public int minNewsRiskRawScore() {
            if (minNewsRiskScore != null) {
                throw new IllegalStateException(
                        "quant.agent.score.min-news-risk-score uses the deprecated weighted 0-5 scale; "
                                + "migrate to min-news-risk-raw-score on the 0-100 scale");
            }
            return minNewsRiskRawScore;
        }

        public void setMinNewsRiskRawScore(int minNewsRiskRawScore) {
            if (minNewsRiskRawScore < 0 || minNewsRiskRawScore > 100) {
                throw new IllegalArgumentException("minNewsRiskRawScore must be between 0 and 100");
            }
            this.minNewsRiskRawScore = minNewsRiskRawScore;
        }
    }

    public static class Market {
        private BigDecimal minVolume24hUsdt = BigDecimal.valueOf(2_000_000);
        private BigDecimal maxSpreadBps = BigDecimal.valueOf(8);
        private BigDecimal minDepthUsdt = BigDecimal.valueOf(5_000);
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
        private int minFinalRankScore = 60;

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
        private String allocationMode = "EQUAL_SPLIT";
        private BigDecimal targetUtilizationPct = BigDecimal.valueOf(100);
        private BigDecimal minTargetUtilizationPct = BigDecimal.valueOf(100);
        private BigDecimal maxUtilizationPct = BigDecimal.valueOf(100);
        private BigDecimal minOrderMarginUsdt = BigDecimal.valueOf(5);
        private BigDecimal preferredMinOrderMarginUsdt = BigDecimal.valueOf(10);
        private BigDecimal maxSinglePositionBudgetPct = new BigDecimal("33.333334");
        private Map<Integer, BigDecimal> slotWeights = new LinkedHashMap<>(
                Map.of(1, new BigDecimal("0.33333334"),
                        2, new BigDecimal("0.33333333"),
                        3, new BigDecimal("0.33333333"))
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
                Map.of(0, 60, 1, 60, 2, 60)
        );
        private Map<Integer, Integer> minFinalRankScoreByOpenPositions = new LinkedHashMap<>(
                Map.of(0, 60, 1, 60, 2, 60)
        );
        private Map<String, Integer> maxPositionsByMarketRegime = new LinkedHashMap<>(
                Map.of("BULLISH", 3, "BEARISH", 3, "CHOPPY", 2, "HIGH_VOLATILITY", 1, "RISK_OFF", 0)
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
                    minScoreByOpenPositions.getOrDefault(2, 60));
        }

        public int minFinalRankScoreForOpenPositions(int openPositions) {
            return minFinalRankScoreByOpenPositions.getOrDefault(openPositions,
                    minFinalRankScoreByOpenPositions.getOrDefault(2, 60));
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
        private int missingDataScore = 70;

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
        /** @deprecated canonical-plan-v1 rejects unexpressible ladders; no merge is performed. */
        @Deprecated(forRemoval = false)
        private boolean mergeSmallTpOrders = true;
        /** @deprecated canonical-plan-v1 always uses explicit allocation/remainder rules. */
        @Deprecated(forRemoval = false)
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

        @Deprecated(forRemoval = false)
        public boolean mergeSmallTpOrders() {
            return mergeSmallTpOrders;
        }

        @Deprecated(forRemoval = false)
        public void setMergeSmallTpOrders(boolean mergeSmallTpOrders) {
            this.mergeSmallTpOrders = mergeSmallTpOrders;
        }

        @Deprecated(forRemoval = false)
        public boolean finalTpUseRemainingSize() {
            return finalTpUseRemainingSize;
        }

        @Deprecated(forRemoval = false)
        public void setFinalTpUseRemainingSize(boolean finalTpUseRemainingSize) {
            this.finalTpUseRemainingSize = finalTpUseRemainingSize;
        }
    }

    public static class AiSelection {
        private boolean enabled = true;
        private int candidateLimit = 12;
        private String policy = "RULE_FIRST_AI_RANK";

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int candidateLimit() {
            return Math.max(1, Math.min(12, candidateLimit));
        }

        public void setCandidateLimit(int candidateLimit) {
            this.candidateLimit = candidateLimit;
        }

        public String policy() {
            return policy == null || policy.isBlank() ? "RULE_FIRST_AI_RANK" : policy.trim().toUpperCase();
        }

        public void setPolicy(String policy) {
            this.policy = policy;
        }
    }

    public static class Risk {
        private BigDecimal singleTradeRiskPercent = BigDecimal.ONE;
        private BigDecimal maxSingleMarginPercent = new BigDecimal("33.333334");
        private BigDecimal maxDailyLossPercent = BigDecimal.valueOf(30);
        private int maxDailyTrades = 5;
        private int maxConsecutiveLosses = 3;
        private int cooldownMinutesAfterLoss = 30;
        private int maxLeverage = 100;

        public BigDecimal singleTradeRiskPercent() {
            return singleTradeRiskPercent;
        }

        public void setSingleTradeRiskPercent(BigDecimal singleTradeRiskPercent) {
            this.singleTradeRiskPercent = singleTradeRiskPercent;
        }

        public BigDecimal maxSingleMarginPercent() {
            return maxSingleMarginPercent;
        }

        public void setMaxSingleMarginPercent(BigDecimal maxSingleMarginPercent) {
            this.maxSingleMarginPercent = maxSingleMarginPercent;
        }

        /**
         * Backward-compatible binding for deployments that still use the old,
         * misleading property name. The value has always represented a margin
         * cap, not the stop-loss budget.
         */
        @Deprecated
        public BigDecimal maxSingleLossPercent() {
            return maxSingleMarginPercent;
        }

        @Deprecated
        public void setMaxSingleLossPercent(BigDecimal maxSingleLossPercent) {
            this.maxSingleMarginPercent = maxSingleLossPercent;
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
        private boolean netProfitExitEnabled;
        public boolean isNetProfitExitEnabled() { return netProfitExitEnabled; }
        public void setNetProfitExitEnabled(boolean enabled) { netProfitExitEnabled = enabled; }

        private int entryTimeoutMinutes = 10;
        private int sidewaysPositionHours = 3;
        private BigDecimal sidewaysPnlRangePct = BigDecimal.ONE;
        private BigDecimal sidewaysExitProfitPct = new BigDecimal("0.3");
        private int lossCloseMinutes = 60;
        private int maxHoldMinutes = 180;
        private int maxHoldHours = 3;
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

        public int lossCloseMinutes() {
            return lossCloseMinutes;
        }

        public void setLossCloseMinutes(int lossCloseMinutes) {
            this.lossCloseMinutes = lossCloseMinutes;
        }

        public int maxHoldMinutes() {
            return maxHoldMinutes > 0 ? maxHoldMinutes : Math.max(1, maxHoldHours) * 60;
        }

        public void setMaxHoldMinutes(int maxHoldMinutes) {
            this.maxHoldMinutes = maxHoldMinutes;
        }

        public int maxHoldHours() {
            return maxHoldHours;
        }

        public void setMaxHoldHours(int maxHoldHours) {
            this.maxHoldHours = maxHoldHours;
            this.maxHoldMinutes = Math.max(1, maxHoldHours) * 60;
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

    /**
     * Dynamic profit protection. All defaults are BASELINE values chosen to stay
     * semantically aligned with the offline policies already evaluated by
     * {@code CounterfactualExitService}; they are not optimised.
     */
    public static class ProfitProtection {
        private boolean enabled = true;
        private BigDecimal breakEvenActivationR = new BigDecimal("0.75");
        private BigDecimal profitLockActivationR = new BigDecimal("1.5");
        private BigDecimal trailingActivationR = new BigDecimal("3.0");
        private BigDecimal givebackRatioPct = new BigDecimal("35");
        private BigDecimal maxGivebackR = new BigDecimal("3.0");
        private BigDecimal roundTripFeePct = new BigDecimal("0.12");
        private BigDecimal estimatedSlippagePct = new BigDecimal("0.05");
        private BigDecimal stopBufferFromMarkPct = new BigDecimal("0.10");
        private int minStopImprovementBps = 5;
        private long minStopAmendIntervalSeconds = 60L;
        private int maxStopAmendsPerHour = 20;
        /** Reserved capability; the cancellation path is deliberately not implemented yet. */
        private boolean trailingCancelRemainingTp = false;

        public boolean enabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public BigDecimal breakEvenActivationR() {
            return breakEvenActivationR;
        }

        public void setBreakEvenActivationR(BigDecimal breakEvenActivationR) {
            this.breakEvenActivationR = breakEvenActivationR;
        }

        public BigDecimal profitLockActivationR() {
            return profitLockActivationR;
        }

        public void setProfitLockActivationR(BigDecimal profitLockActivationR) {
            this.profitLockActivationR = profitLockActivationR;
        }

        public BigDecimal trailingActivationR() {
            return trailingActivationR;
        }

        public void setTrailingActivationR(BigDecimal trailingActivationR) {
            this.trailingActivationR = trailingActivationR;
        }

        public BigDecimal givebackRatioPct() {
            return givebackRatioPct;
        }

        public void setGivebackRatioPct(BigDecimal givebackRatioPct) {
            this.givebackRatioPct = givebackRatioPct;
        }

        public BigDecimal maxGivebackR() {
            return maxGivebackR;
        }

        public void setMaxGivebackR(BigDecimal maxGivebackR) {
            this.maxGivebackR = maxGivebackR;
        }

        public BigDecimal roundTripFeePct() {
            return roundTripFeePct;
        }

        public void setRoundTripFeePct(BigDecimal roundTripFeePct) {
            this.roundTripFeePct = roundTripFeePct;
        }

        public BigDecimal estimatedSlippagePct() {
            return estimatedSlippagePct;
        }

        public void setEstimatedSlippagePct(BigDecimal estimatedSlippagePct) {
            this.estimatedSlippagePct = estimatedSlippagePct;
        }

        public BigDecimal stopBufferFromMarkPct() {
            return stopBufferFromMarkPct;
        }

        public void setStopBufferFromMarkPct(BigDecimal stopBufferFromMarkPct) {
            this.stopBufferFromMarkPct = stopBufferFromMarkPct;
        }

        public int minStopImprovementBps() {
            return minStopImprovementBps;
        }

        public void setMinStopImprovementBps(int minStopImprovementBps) {
            this.minStopImprovementBps = minStopImprovementBps;
        }

        public long minStopAmendIntervalSeconds() {
            return minStopAmendIntervalSeconds;
        }

        public void setMinStopAmendIntervalSeconds(long minStopAmendIntervalSeconds) {
            this.minStopAmendIntervalSeconds = minStopAmendIntervalSeconds;
        }

        public int maxStopAmendsPerHour() {
            return maxStopAmendsPerHour;
        }

        public void setMaxStopAmendsPerHour(int maxStopAmendsPerHour) {
            this.maxStopAmendsPerHour = maxStopAmendsPerHour;
        }

        public boolean trailingCancelRemainingTp() {
            return trailingCancelRemainingTp;
        }

        public void setTrailingCancelRemainingTp(boolean trailingCancelRemainingTp) {
            this.trailingCancelRemainingTp = trailingCancelRemainingTp;
        }

        public com.example.quant.agent.exit.ProfitProtectionConfig toConfig() {
            return new com.example.quant.agent.exit.ProfitProtectionConfig(
                    enabled, breakEvenActivationR, profitLockActivationR, trailingActivationR,
                    givebackRatioPct, maxGivebackR, roundTripFeePct, estimatedSlippagePct,
                    stopBufferFromMarkPct, minStopImprovementBps, minStopAmendIntervalSeconds,
                    maxStopAmendsPerHour, trailingCancelRemainingTp);
        }
    }

    /**
     * V2 Selection 配置：Universe 硬资格阈值、Provider TTL 等。
     */
    public static class Selection {
        private Universe universe = new Universe();
        private Providers providers = new Providers();
        private Breakout breakout = new Breakout();
        /** 深度分析合约数量上限；null 表示 {@code topCandidateLimit * 3}。 */
        private Integer deepAnalysisLimit;
        /** 并行分析合约数量。 */
        private int deepAnalysisConcurrency = 4;
        /** 每轮从深度分析总名额中分配给最久未分析合约的数量。 */
        private int deepAnalysisRotationQuota = 20;

        public Integer deepAnalysisLimit() {
            return deepAnalysisLimit;
        }

        public void setDeepAnalysisLimit(Integer deepAnalysisLimit) {
            this.deepAnalysisLimit = deepAnalysisLimit;
        }

        public int deepAnalysisRotationQuota() {
            return deepAnalysisRotationQuota;
        }

        public void setDeepAnalysisRotationQuota(int deepAnalysisRotationQuota) {
            this.deepAnalysisRotationQuota = Math.max(0, deepAnalysisRotationQuota);
        }

        public int deepAnalysisConcurrency() {
            return deepAnalysisConcurrency;
        }

        public void setDeepAnalysisConcurrency(int deepAnalysisConcurrency) {
            this.deepAnalysisConcurrency = Math.max(1, deepAnalysisConcurrency);
        }

        public Universe universe() {
            return universe;
        }

        public void setUniverse(Universe universe) {
            this.universe = universe == null ? new Universe() : universe;
        }

        public Providers providers() {
            return providers;
        }

        public void setProviders(Providers providers) {
            this.providers = providers == null ? new Providers() : providers;
        }

        public Breakout breakout() { return breakout; }
        public void setBreakout(Breakout breakout) { this.breakout = breakout == null ? new Breakout() : breakout; }
    }

    public static class Breakout {
        private BigDecimal atAthPct = new BigDecimal("-2");
        private BigDecimal nearAthPct = new BigDecimal("-10");
        private BigDecimal midRangePct = new BigDecimal("-40");

        public BigDecimal atAthPct() { return atAthPct; }
        public void setAtAthPct(BigDecimal value) { this.atAthPct = value; }
        public BigDecimal nearAthPct() { return nearAthPct; }
        public void setNearAthPct(BigDecimal value) { this.nearAthPct = value; }
        public BigDecimal midRangePct() { return midRangePct; }
        public void setMidRangePct(BigDecimal value) { this.midRangePct = value; }
    }

    public static class Universe {
        /** 最小市值（USD）。默认 20M。 */
        private BigDecimal minMarketCapUsd = BigDecimal.valueOf(20_000_000L);
        /** 最小流通率（0..1）。默认 0.50。 */
        private BigDecimal minCirculationRatio = new BigDecimal("0.50");
        /** 最小 Volume/MarketCap（0..N）。默认 0.20。 */
        private BigDecimal minVolumeMcapRatio = new BigDecimal("0.20");
        /** 是否启用 Volume/MCap 硬资格。回放校准后可显式启用。 */
        private boolean volumeMcapEnabled = false;
        /** 是否启用 Circulation 硬资格。回放校准后可显式启用。 */
        private boolean circulationEnabled = false;
        /** 是否启用 MarketCap 硬资格。默认 true（生产无 Provider 时也会硬拒所有候选）。 */
        private boolean marketCapEnabled = true;

        public BigDecimal minMarketCapUsd() {
            return minMarketCapUsd;
        }

        public void setMinMarketCapUsd(BigDecimal minMarketCapUsd) {
            this.minMarketCapUsd = minMarketCapUsd;
        }

        public BigDecimal minCirculationRatio() {
            return minCirculationRatio;
        }

        public void setMinCirculationRatio(BigDecimal minCirculationRatio) {
            this.minCirculationRatio = minCirculationRatio;
        }

        public BigDecimal minVolumeMcapRatio() {
            return minVolumeMcapRatio;
        }

        public void setMinVolumeMcapRatio(BigDecimal minVolumeMcapRatio) {
            this.minVolumeMcapRatio = minVolumeMcapRatio;
        }

        public boolean volumeMcapEnabled() {
            return volumeMcapEnabled;
        }

        public void setVolumeMcapEnabled(boolean volumeMcapEnabled) {
            this.volumeMcapEnabled = volumeMcapEnabled;
        }

        public boolean circulationEnabled() {
            return circulationEnabled;
        }

        public void setCirculationEnabled(boolean circulationEnabled) {
            this.circulationEnabled = circulationEnabled;
        }

        public boolean marketCapEnabled() {
            return marketCapEnabled;
        }

        public void setMarketCapEnabled(boolean marketCapEnabled) {
            this.marketCapEnabled = marketCapEnabled;
        }

    }

    public static class Providers {
        /** MarketCap Provider fresh TTL（秒）。默认 600。 */
        private int marketCapFreshTtlSeconds = 600;
        /** MarketCap Provider stale TTL（秒）。默认 600。超过 stale 视为无效。 */
        private int marketCapStaleTtlSeconds = 600;
        /** 是否启用 MarketCap Provider。默认 true（即使 null provider 也会硬拒所有候选）。 */
        private boolean marketCapEnabled = true;
        private boolean relativeStrengthEnabled = true;
        private int relativeStrengthFreshTtlSeconds = 60;
        private int relativeStrengthStaleTtlSeconds = 600;
        private boolean athBreakoutEnabled = true;
        private int athBreakoutFreshTtlSeconds = 600;
        private int athBreakoutStaleTtlSeconds = 3600;
        private int athBreakoutMaxPages = 50;
        /** Phase C/D：CoinGecko 市值数据源。 */
        private CoinGecko coingecko = new CoinGecko();
        /**
         * Wave 2 — Derivatives（OI History + Funding History）Provider 配置。
         * 默认实现：{@code OkxOpenInterestHistoryProvider} /
         * {@code OkxFundingHistoryProvider}（在 {@code OkxContractScanner} V2 路径注入）。
         */
        private Derivatives derivatives = new Derivatives();

        public int marketCapFreshTtlSeconds() {
            return marketCapFreshTtlSeconds;
        }

        public void setMarketCapFreshTtlSeconds(int marketCapFreshTtlSeconds) {
            this.marketCapFreshTtlSeconds = marketCapFreshTtlSeconds;
        }

        public int marketCapStaleTtlSeconds() {
            return marketCapStaleTtlSeconds;
        }

        public void setMarketCapStaleTtlSeconds(int marketCapStaleTtlSeconds) {
            this.marketCapStaleTtlSeconds = marketCapStaleTtlSeconds;
        }

        public boolean marketCapEnabled() {
            return marketCapEnabled;
        }

        public void setMarketCapEnabled(boolean marketCapEnabled) {
            this.marketCapEnabled = marketCapEnabled;
        }

        public boolean relativeStrengthEnabled() {
            return relativeStrengthEnabled;
        }

        public void setRelativeStrengthEnabled(boolean enabled) {
            this.relativeStrengthEnabled = enabled;
        }

        public int relativeStrengthFreshTtlSeconds() {
            return relativeStrengthFreshTtlSeconds;
        }

        public void setRelativeStrengthFreshTtlSeconds(int seconds) {
            this.relativeStrengthFreshTtlSeconds = seconds;
        }

        public int relativeStrengthStaleTtlSeconds() {
            return relativeStrengthStaleTtlSeconds;
        }

        public void setRelativeStrengthStaleTtlSeconds(int seconds) {
            this.relativeStrengthStaleTtlSeconds = seconds;
        }

        public boolean athBreakoutEnabled() { return athBreakoutEnabled; }
        public void setAthBreakoutEnabled(boolean enabled) { this.athBreakoutEnabled = enabled; }
        public int athBreakoutFreshTtlSeconds() { return athBreakoutFreshTtlSeconds; }
        public void setAthBreakoutFreshTtlSeconds(int seconds) { this.athBreakoutFreshTtlSeconds = seconds; }
        public int athBreakoutStaleTtlSeconds() { return athBreakoutStaleTtlSeconds; }
        public void setAthBreakoutStaleTtlSeconds(int seconds) { this.athBreakoutStaleTtlSeconds = seconds; }
        public int athBreakoutMaxPages() { return athBreakoutMaxPages; }
        public void setAthBreakoutMaxPages(int pages) { this.athBreakoutMaxPages = pages; }

        public CoinGecko getCoingecko() {
            return coingecko == null ? new CoinGecko() : coingecko;
        }

        public void setCoingecko(CoinGecko coingecko) {
            this.coingecko = coingecko == null ? new CoinGecko() : coingecko;
        }

        public Derivatives derivatives() {
            return derivatives == null ? new Derivatives() : derivatives;
        }

        public void setDerivatives(Derivatives derivatives) {
            this.derivatives = derivatives == null ? new Derivatives() : derivatives;
        }

        /**
         * Wave 2 — Derivatives Provider TTL 与极端资金费率阈值。
         * <ul>
         *   <li>OI History TTL 短（60s/600s），与 MarketCap 同档位</li>
         *   <li>Funding History TTL 长（300s/3600s），funding 8h 一次刷新，无需高频拉取</li>
         *   <li>{@code extremeFundingRate} = 0.0030（绝对值）：{@code |rate24h| >= 该阈值} 视为极端</li>
         * </ul>
         */
        public static class Derivatives {
            /** OI History Provider fresh TTL（秒）。默认 60。 */
            private int openInterestFreshTtlSeconds = 60;
            /** OI History Provider stale TTL（秒）。默认 600。 */
            private int openInterestStaleTtlSeconds = 600;
            /** Funding History Provider fresh TTL（秒）。默认 300。 */
            private int fundingFreshTtlSeconds = 300;
            /** Funding History Provider stale TTL（秒）。默认 3600。 */
            private int fundingStaleTtlSeconds = 3600;
            /** 极端资金费率阈值（绝对值）。{@code |rate24h| >= 该值} 视为 EXTREME。默认 0.0030。 */
            private java.math.BigDecimal extremeFundingRate = new java.math.BigDecimal("0.0030");
            /** 是否启用 OI History Provider。默认 true。 */
            private boolean openInterestEnabled = true;
            /** 是否启用 Funding History Provider。默认 true。 */
            private boolean fundingEnabled = true;

            public int openInterestFreshTtlSeconds() {
                return openInterestFreshTtlSeconds;
            }

            public void setOpenInterestFreshTtlSeconds(int v) {
                this.openInterestFreshTtlSeconds = v;
            }

            public int openInterestStaleTtlSeconds() {
                return openInterestStaleTtlSeconds;
            }

            public void setOpenInterestStaleTtlSeconds(int v) {
                this.openInterestStaleTtlSeconds = v;
            }

            public int fundingFreshTtlSeconds() {
                return fundingFreshTtlSeconds;
            }

            public void setFundingFreshTtlSeconds(int v) {
                this.fundingFreshTtlSeconds = v;
            }

            public int fundingStaleTtlSeconds() {
                return fundingStaleTtlSeconds;
            }

            public void setFundingStaleTtlSeconds(int v) {
                this.fundingStaleTtlSeconds = v;
            }

            public java.math.BigDecimal extremeFundingRate() {
                return extremeFundingRate == null ? new java.math.BigDecimal("0.0030") : extremeFundingRate;
            }

            public void setExtremeFundingRate(java.math.BigDecimal v) {
                this.extremeFundingRate = v;
            }

            public boolean openInterestEnabled() {
                return openInterestEnabled;
            }

            public void setOpenInterestEnabled(boolean v) {
                this.openInterestEnabled = v;
            }

            public boolean fundingEnabled() {
                return fundingEnabled;
            }

            public void setFundingEnabled(boolean v) {
                this.fundingEnabled = v;
            }
        }

        /**
         * Phase C/D — CoinGecko 配置。
         * 环境变量 {@code COINGECKO_API_KEY} 会覆盖 yaml 的 apiKey；用于 Pro tier。
         */
        public static class CoinGecko {
            private boolean enabled = true;
            private String apiKey = "";
            private String apiMode = "DEMO";
            private int rateLimitPerMinute = 20;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getApiKey() {
                return apiKey == null ? "" : apiKey;
            }

            public void setApiKey(String apiKey) {
                this.apiKey = apiKey == null ? "" : apiKey;
            }

            public String getApiMode() {
                return apiMode == null || apiMode.isBlank() ? "DEMO" : apiMode.trim().toUpperCase();
            }

            public void setApiMode(String apiMode) {
                this.apiMode = apiMode == null ? "DEMO" : apiMode;
            }

            public int getRateLimitPerMinute() {
                return rateLimitPerMinute <= 0 ? 20 : rateLimitPerMinute;
            }

            public void setRateLimitPerMinute(int rateLimitPerMinute) {
                this.rateLimitPerMinute = rateLimitPerMinute;
            }
        }
    }
}
