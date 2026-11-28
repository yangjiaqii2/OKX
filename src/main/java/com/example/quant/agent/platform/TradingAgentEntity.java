package com.example.quant.agent.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "quant_trading_agent")
public class TradingAgentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 128)
    private String userName;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "template_code", nullable = false, length = 32)
    private String templateCode = TradingAgentTemplate.TREND_BALANCED.name();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TradingAgentStatus status = TradingAgentStatus.PAUSED;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 32)
    private TradingAgentRunMode runMode = TradingAgentRunMode.PAPER;

    @Column(name = "llm_profile_id")
    private Long llmProfileId;

    @Column(name = "allocated_budget_usdt", nullable = false, precision = 18, scale = 4)
    private BigDecimal allocatedBudgetUsdt = new BigDecimal("100");

    @Column(name = "max_open_positions", nullable = false)
    private int maxOpenPositions = 2;

    @Column(name = "max_leverage", nullable = false)
    private int maxLeverage = 5;

    @Column(name = "min_total_score", nullable = false)
    private int minTotalScore = 60;

    @Column(name = "min_final_rank_score", nullable = false)
    private int minFinalRankScore = 60;

    @Column(name = "max_single_loss_pct", nullable = false, precision = 6, scale = 3)
    private BigDecimal maxSingleLossPct = new BigDecimal("1.5");

    @Column(name = "max_daily_loss_pct", nullable = false, precision = 6, scale = 3)
    private BigDecimal maxDailyLossPct = new BigDecimal("4.0");

    @Enumerated(EnumType.STRING)
    @Column(name = "direction_preference", nullable = false, length = 16)
    private TradingAgentDirection directionPreference = TradingAgentDirection.BOTH;

    @Column(name = "symbol_whitelist", nullable = false, length = 512)
    private String symbolWhitelist = "";

    @Column(name = "symbol_blacklist", nullable = false, length = 512)
    private String symbolBlacklist = "";

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt = "";

    @Column(name = "tool_calling_enabled", nullable = false)
    private boolean toolCallingEnabled = true;

    @Column(name = "eval_interval_minutes", nullable = false)
    private int evalIntervalMinutes = 5;

    @Column(name = "version_no", nullable = false)
    private int versionNo = 1;

    @Column(name = "last_decision", length = 32)
    private String lastDecision;

    @Column(name = "last_summary", length = 512)
    private String lastSummary;

    @Column(name = "last_error_code", length = 128)
    private String lastErrorCode;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public TradingAgentStatus getStatus() { return status; }
    public void setStatus(TradingAgentStatus status) { this.status = status; }
    public TradingAgentRunMode getRunMode() { return runMode; }
    public void setRunMode(TradingAgentRunMode runMode) { this.runMode = runMode; }
    public Long getLlmProfileId() { return llmProfileId; }
    public void setLlmProfileId(Long llmProfileId) { this.llmProfileId = llmProfileId; }
    public BigDecimal getAllocatedBudgetUsdt() { return allocatedBudgetUsdt; }
    public void setAllocatedBudgetUsdt(BigDecimal allocatedBudgetUsdt) { this.allocatedBudgetUsdt = allocatedBudgetUsdt; }
    public int getMaxOpenPositions() { return maxOpenPositions; }
    public void setMaxOpenPositions(int maxOpenPositions) { this.maxOpenPositions = maxOpenPositions; }
    public int getMaxLeverage() { return maxLeverage; }
    public void setMaxLeverage(int maxLeverage) { this.maxLeverage = maxLeverage; }
    public int getMinTotalScore() { return minTotalScore; }
    public void setMinTotalScore(int minTotalScore) { this.minTotalScore = minTotalScore; }
    public int getMinFinalRankScore() { return minFinalRankScore; }
    public void setMinFinalRankScore(int minFinalRankScore) { this.minFinalRankScore = minFinalRankScore; }
    public BigDecimal getMaxSingleLossPct() { return maxSingleLossPct; }
    public void setMaxSingleLossPct(BigDecimal maxSingleLossPct) { this.maxSingleLossPct = maxSingleLossPct; }
    public BigDecimal getMaxDailyLossPct() { return maxDailyLossPct; }
    public void setMaxDailyLossPct(BigDecimal maxDailyLossPct) { this.maxDailyLossPct = maxDailyLossPct; }
    public TradingAgentDirection getDirectionPreference() { return directionPreference; }
    public void setDirectionPreference(TradingAgentDirection directionPreference) { this.directionPreference = directionPreference; }
    public String getSymbolWhitelist() { return symbolWhitelist; }
    public void setSymbolWhitelist(String symbolWhitelist) { this.symbolWhitelist = symbolWhitelist; }
    public String getSymbolBlacklist() { return symbolBlacklist; }
    public void setSymbolBlacklist(String symbolBlacklist) { this.symbolBlacklist = symbolBlacklist; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public boolean isToolCallingEnabled() { return toolCallingEnabled; }
    public void setToolCallingEnabled(boolean toolCallingEnabled) { this.toolCallingEnabled = toolCallingEnabled; }
    public int getEvalIntervalMinutes() { return evalIntervalMinutes; }
    public void setEvalIntervalMinutes(int evalIntervalMinutes) { this.evalIntervalMinutes = evalIntervalMinutes; }
    public int getVersionNo() { return versionNo; }
    public void setVersionNo(int versionNo) { this.versionNo = versionNo; }
    public String getLastDecision() { return lastDecision; }
    public void setLastDecision(String lastDecision) { this.lastDecision = lastDecision; }
    public String getLastSummary() { return lastSummary; }
    public void setLastSummary(String lastSummary) { this.lastSummary = lastSummary; }
    public String getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(String lastErrorCode) { this.lastErrorCode = lastErrorCode; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
