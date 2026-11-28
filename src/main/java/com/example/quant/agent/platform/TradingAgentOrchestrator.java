package com.example.quant.agent.platform;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.ai.LlmRuntimeConfig;
import com.example.quant.ai.UserLlmProfileService;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.crypto.selection.V2SelectionScoreResult;
import com.example.quant.market.DirectionBias;
import com.example.quant.market.MarketType;
import com.example.quant.order.OrderConfirmService;
import com.example.quant.order.OrderExecutionResult;
import com.example.quant.order.PendingOrder;
import com.example.quant.order.PendingOrderService;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.TradePlan;
import com.example.quant.tradeplan.TradePlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TradingAgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(TradingAgentOrchestrator.class);
    private static final int TOP_LLM_CANDIDATES = 3;
    private static final int MAX_READ_ONLY_TOOL_STEPS = 2;

    private final TradingAgentRepository agentRepository;
    private final AgentRunRepository runRepository;
    private final TradingAgentService agentService;
    private final UserLlmProfileService llmProfileService;
    private final AiAnalysisService aiAnalysisService;
    private final OkxCredentialStore okxCredentialStore;
    private final ContractAnalysisService contractAnalysisService;
    private final TradePlanService tradePlanService;
    private final PendingOrderService pendingOrderService;
    private final ObjectMapper objectMapper;
    private OrderConfirmService orderConfirmService;
    private TradingProperties tradingProperties;
    private SystemControlService systemControlService;
    private AgentMemoryService memoryService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureAutoExecution(
            OrderConfirmService orderConfirmService,
            TradingProperties tradingProperties,
            SystemControlService systemControlService
    ) {
        this.orderConfirmService = orderConfirmService;
        this.tradingProperties = tradingProperties;
        this.systemControlService = systemControlService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void configureMemoryService(AgentMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public TradingAgentOrchestrator(
            TradingAgentRepository agentRepository,
            AgentRunRepository runRepository,
            TradingAgentService agentService,
            UserLlmProfileService llmProfileService,
            AiAnalysisService aiAnalysisService,
            OkxCredentialStore okxCredentialStore,
            ContractAnalysisService contractAnalysisService,
            TradePlanService tradePlanService,
            PendingOrderService pendingOrderService,
            ObjectMapper objectMapper
    ) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.agentService = agentService;
        this.llmProfileService = llmProfileService;
        this.aiAnalysisService = aiAnalysisService;
        this.okxCredentialStore = okxCredentialStore;
        this.contractAnalysisService = contractAnalysisService;
        this.tradePlanService = tradePlanService;
        this.pendingOrderService = pendingOrderService;
        this.objectMapper = objectMapper;
    }

    public int dispatchScan(
            String scanId,
            List<ContractCandidate> candidates,
            Map<String, V2SelectionScoreResult> v2Scores
    ) {
        List<TradingAgentEntity> runningAgents = agentRepository.findByStatusOrderByUpdatedAtAsc(TradingAgentStatus.RUNNING);
        if (runningAgents.isEmpty()) {
            return 0;
        }
        Map<String, Set<String>> claimedSymbolsByUser = new HashMap<>();
        int executed = 0;
        for (TradingAgentEntity agent : runningAgents) {
            Set<String> userClaimed = claimedSymbolsByUser.computeIfAbsent(agent.getUserName(), k -> new HashSet<>());
            try {
                executeAgentRound(agent, scanId, candidates == null ? List.of() : candidates,
                        v2Scores == null ? Map.of() : v2Scores, userClaimed);
                executed++;
            } catch (Exception ex) {
                log.warn("Agent dispatch failed agentId={} user={} scanId={} error={}",
                        agent.getId(), agent.getUserName(), scanId, ex.getMessage());
            }
        }
        return executed;
    }

    public AgentRunView runSingleAgentNow(Long agentId) {
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        TradingAgentEntity agent = agentService.requireOwned(owner, agentId);
        List<ContractCandidate> candidates = contractAnalysisService.candidates();
        String scanId = "manual-" + UUID.randomUUID().toString().substring(0, 8);
        AgentRunEntity run = executeAgentRound(agent, scanId, candidates == null ? List.of() : candidates, Map.of(), new HashSet<>());
        return agentService.toRunView(run);
    }

    AgentRunEntity executeAgentRound(
            TradingAgentEntity agent,
            String scanId,
            List<ContractCandidate> candidates,
            Map<String, V2SelectionScoreResult> v2Scores,
            Set<String> userClaimedSymbols
    ) {
        long startedAt = System.nanoTime();
        List<AgentRunStep> steps = new ArrayList<>();
        int stepNo = 1;
        String decision = "SKIP";
        String selectedInstId = null;
        String selectedDirection = null;
        String llmModel = null;
        String summaryReason = "";
        String errorCode = null;

        try {
            // Step 1: Deterministic Filter + Symbol Exclusivity Lock
            long filterStart = System.nanoTime();
            List<ContractCandidate> filtered = filterCandidates(agent, candidates, userClaimedSymbols);
            steps.add(new AgentRunStep(
                    stepNo++,
                    "FILTER",
                    filtered.isEmpty() ? "SKIPPED" : "PASSED",
                    filtered.isEmpty()
                            ? "共享候选池共 " + candidates.size() + " 个，按该 Agent 评分/方向/白黑名单与同账户币种排他锁过滤后无合格标的"
                            : "从 " + candidates.size() + " 个共享候选中筛出 " + filtered.size() + " 个标的: "
                                    + filtered.stream().map(ContractCandidate::instId).collect(Collectors.joining(", ")),
                    elapsedMs(filterStart)
            ));

            if (filtered.isEmpty()) {
                summaryReason = "本轮无满足该 Agent 过滤门槛的候选合约";
                return persistRun(agent, scanId, decision, null, null, null, summaryReason, null, steps, elapsedMs(startedAt));
            }

            // Step 2: Resolve User BYOK LLM Profile & Structured Decision
            long llmStart = System.nanoTime();
            Optional<LlmRuntimeConfig> runtimeOpt = llmProfileService.resolveRuntimeConfig(agent.getUserName(), agent.getLlmProfileId());
            if (runtimeOpt.isEmpty()) {
                decision = "BLOCKED";
                errorCode = "LLM_PROFILE_MISSING";
                summaryReason = "未配置个人大模型 API Key（BYOK），已拦截本轮决策";
                steps.add(new AgentRunStep(stepNo++, "LLM_DECISION", "BLOCKED", summaryReason, elapsedMs(llmStart)));
                return persistRun(agent, scanId, decision, null, null, null, summaryReason, errorCode, steps, elapsedMs(startedAt));
            }

            LlmRuntimeConfig runtime = runtimeOpt.get();
            llmModel = runtime.modelName();
            String memoryContext = memoryService == null
                    ? ""
                    : memoryService.buildMemoryContext(agent.getId(), agent.getUserName());
            String systemPrompt = buildSystemPrompt(agent, agent.isToolCallingEnabled());
            StringBuilder promptBuilder = new StringBuilder(buildCandidateDigestPrompt(agent, filtered, v2Scores, memoryContext));

            JsonNode llmJson = aiAnalysisService.completeJson(runtime, systemPrompt, promptBuilder.toString());
            int toolCallsUsed = 0;
            while (agent.isToolCallingEnabled()
                    && toolCallsUsed < MAX_READ_ONLY_TOOL_STEPS
                    && "TOOL_CALL".equalsIgnoreCase(llmJson.path("action").asText("").trim())) {
                long toolStart = System.nanoTime();
                String toolName = llmJson.path("tool").asText("").trim();
                String targetInst = llmJson.path("instId").asText("").trim().toUpperCase();
                String toolResult = executeReadOnlyTool(agent, filtered, toolName, targetInst);
                toolCallsUsed++;
                steps.add(new AgentRunStep(
                        stepNo++,
                        "TOOL_CALL",
                        "PASSED",
                        toolName + "(" + (targetInst.isEmpty() ? "-" : targetInst) + ") → " + truncate(toolResult, 260),
                        elapsedMs(toolStart)
                ));
                promptBuilder.append("\n\n[只读工具返回 #").append(toolCallsUsed).append("] ")
                        .append(toolName).append("(").append(targetInst).append("): ")
                        .append(toolResult)
                        .append(toolCallsUsed >= MAX_READ_ONLY_TOOL_STEPS
                                ? "\n（已达工具调用上限，请立即给出最终 TRADE 或 SKIP JSON 决策）"
                                : "\n请继续给出最终 TRADE 或 SKIP JSON 决策（或最多再查 1 次）。");
                llmJson = aiAnalysisService.completeJson(runtime, systemPrompt, promptBuilder.toString());
            }

            String action = llmJson.path("action").asText("SKIP").trim().toUpperCase();
            String pickedInstId = llmJson.path("instId").asText("").trim().toUpperCase();
            String llmReason = llmJson.path("reason").asText("模型未提供说明").trim();

            if (!"TRADE".equals(action) || pickedInstId.isEmpty()) {
                decision = "SKIP";
                summaryReason = "AI 本轮观望：" + truncate(llmReason, 400);
                steps.add(new AgentRunStep(stepNo++, "LLM_DECISION", "SKIPPED",
                        "模型 " + llmModel + " 判定 SKIP: " + truncate(llmReason, 300), elapsedMs(llmStart)));
                return persistRun(agent, scanId, decision, null, null, llmModel, summaryReason, null, steps, elapsedMs(startedAt));
            }

            ContractCandidate chosen = filtered.stream()
                    .filter(c -> pickedInstId.equalsIgnoreCase(c.instId()))
                    .findFirst()
                    .orElse(null);
            if (chosen == null) {
                decision = "BLOCKED";
                errorCode = "LLM_HALLUCINATED_SYMBOL";
                summaryReason = "模型返回了不在合格候选池中的标的 " + pickedInstId + "，已被确定性护栏拦截";
                steps.add(new AgentRunStep(stepNo++, "LLM_DECISION", "BLOCKED", summaryReason, elapsedMs(llmStart)));
                return persistRun(agent, scanId, decision, pickedInstId, null, llmModel, summaryReason, errorCode, steps, elapsedMs(startedAt));
            }

            selectedInstId = chosen.instId();
            selectedDirection = chosen.trendDirection() == DirectionBias.BEARISH ? "SHORT" : "LONG";
            steps.add(new AgentRunStep(stepNo++, "LLM_DECISION", "PASSED",
                    "模型 " + llmModel + " 选择 " + selectedInstId + " (" + selectedDirection + "): " + truncate(llmReason, 280),
                    elapsedMs(llmStart)));

            // Step 3: Deterministic Risk Gate + Same-Account Symbol Exclusivity
            long gateStart = System.nanoTime();
            if (!userClaimedSymbols.add(selectedInstId)) {
                decision = "BLOCKED";
                errorCode = "SKIPPED_SYMBOL_OWNED_BY_OTHER_AGENT";
                summaryReason = "同账户已有其他 Agent 占用 " + selectedInstId + "，触发币种排他锁跳过";
                steps.add(new AgentRunStep(stepNo++, "RISK_GATE", "BLOCKED", summaryReason, elapsedMs(gateStart)));
                return persistRun(agent, scanId, decision, selectedInstId, selectedDirection, llmModel, summaryReason, errorCode, steps, elapsedMs(startedAt));
            }

            if (agent.getRunMode() != TradingAgentRunMode.PAPER
                    && okxCredentialStore.findActive(agent.getUserName()).isEmpty()) {
                decision = "BLOCKED";
                errorCode = "OKX_CREDENTIAL_UNBOUND";
                summaryReason = "未绑定个人 OKX 密钥，无法进入 " + agent.getRunMode() + " 执行";
                steps.add(new AgentRunStep(stepNo++, "RISK_GATE", "BLOCKED", summaryReason, elapsedMs(gateStart)));
                return persistRun(agent, scanId, decision, selectedInstId, selectedDirection, llmModel, summaryReason, errorCode, steps, elapsedMs(startedAt));
            }

            steps.add(new AgentRunStep(stepNo++, "RISK_GATE", "PASSED",
                    "通过硬风控校验（预算 " + agent.getAllocatedBudgetUsdt().stripTrailingZeros().toPlainString()
                            + " USDT / 杠杆 ≤ " + agent.getMaxLeverage()
                            + "x / 单笔止损 ≤ " + agent.getMaxSingleLossPct().stripTrailingZeros().toPlainString()
                            + "% / 币种排他锁已获取）",
                    elapsedMs(gateStart)));

            // Step 4: Execution Mode Routing
            long execStart = System.nanoTime();
            if (agent.getRunMode() == TradingAgentRunMode.PAPER) {
                decision = "PAPER_TRADE";
                summaryReason = "[PAPER 模拟] " + selectedInstId + " " + selectedDirection
                        + " @ " + formatNum(chosen.entryPrice())
                        + " (SL " + formatNum(chosen.stopLossPrice()) + " / TP " + formatNum(chosen.takeProfitPrice()) + ") · "
                        + truncate(llmReason, 220);
                steps.add(new AgentRunStep(stepNo++, "EXECUTION", "PASSED", summaryReason, elapsedMs(execStart)));
            } else if (agent.getRunMode() == TradingAgentRunMode.MANUAL_CONFIRM) {
                String instToCreate = selectedInstId;
                PendingOrder pending = AuthUserContext.callAs(agent.getUserName(), () -> {
                    TradePlan plan = tradePlanService.createContractPlan(instToCreate);
                    return pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, plan);
                });
                decision = "MANUAL_ORDER_CREATED";
                summaryReason = "已生成待确认订单 " + pending.id() + " (" + selectedInstId + " " + selectedDirection + ")，请前往「待确认订单」复核下单";
                steps.add(new AgentRunStep(stepNo++, "EXECUTION", "PASSED", summaryReason, elapsedMs(execStart)));
            } else {
                if (systemControlService != null && systemControlService.emergencyStopEnabled()) {
                    decision = "BLOCKED";
                    errorCode = "PLATFORM_EMERGENCY_STOPPED";
                    summaryReason = "平台已触发全局紧急停止，所有 AUTO 自动下单已暂停";
                    steps.add(new AgentRunStep(stepNo++, "EXECUTION", "BLOCKED", summaryReason, elapsedMs(execStart)));
                } else if (tradingProperties == null || !tradingProperties.mode().writesEnabled()) {
                    decision = "BLOCKED";
                    errorCode = "TRADING_MODE_DISABLED";
                    summaryReason = "当前环境交易模式为 DISABLED，AUTO 模式仅在 DEMO 或 LIVE 环境允许下单";
                    steps.add(new AgentRunStep(stepNo++, "EXECUTION", "BLOCKED", summaryReason, elapsedMs(execStart)));
                } else if (orderConfirmService == null) {
                    decision = "BLOCKED";
                    errorCode = "ORDER_CONFIRM_SERVICE_UNAVAILABLE";
                    summaryReason = "自动下单执行器不可用";
                    steps.add(new AgentRunStep(stepNo++, "EXECUTION", "BLOCKED", summaryReason, elapsedMs(execStart)));
                } else {
                    String instToCreate = selectedInstId;
                    BigDecimal perTradeMargin = agent.getAllocatedBudgetUsdt()
                            .divide(BigDecimal.valueOf(Math.max(1, agent.getMaxOpenPositions())), 2, RoundingMode.DOWN)
                            .max(BigDecimal.ONE);
                    OrderExecutionResult execResult = AuthUserContext.callAs(agent.getUserName(), () -> {
                        TradePlan plan = tradePlanService.createContractPlan(instToCreate);
                        PendingOrder pending = pendingOrderService.createPendingOrder(MarketType.OKX_SWAP, plan);
                        return orderConfirmService.confirmAuto(pending.id(), perTradeMargin);
                    });
                    decision = execResult.executed() ? "AUTO_ORDER_EXECUTED" : "BLOCKED";
                    summaryReason = (execResult.executed() ? "AUTO 自动下单已提交" : "AUTO 下单被风控/网关拦截")
                            + " (" + selectedInstId + " " + selectedDirection
                            + ", 保证金 " + perTradeMargin.toPlainString() + " USDT"
                            + (execResult.message() != null ? ", " + truncate(execResult.message(), 160) : "") + ")";
                    steps.add(new AgentRunStep(stepNo++, "EXECUTION", execResult.executed() ? "PASSED" : "BLOCKED",
                            summaryReason, elapsedMs(execStart)));
                }
            }

            return persistRun(agent, scanId, decision, selectedInstId, selectedDirection, llmModel, summaryReason, errorCode, steps, elapsedMs(startedAt));
        } catch (Exception ex) {
            decision = "ERROR";
            errorCode = ex.getClass().getSimpleName();
            summaryReason = "本轮运行异常：" + truncate(ex.getMessage() == null ? errorCode : ex.getMessage(), 400);
            steps.add(new AgentRunStep(stepNo, "EXECUTION", "ERROR", summaryReason, 0L));
            return persistRun(agent, scanId, decision, selectedInstId, selectedDirection, llmModel, summaryReason, errorCode, steps, elapsedMs(startedAt));
        }
    }

    private List<ContractCandidate> filterCandidates(
            TradingAgentEntity agent,
            List<ContractCandidate> candidates,
            Set<String> userClaimedSymbols
    ) {
        Set<String> whitelist = parseSymbols(agent.getSymbolWhitelist());
        Set<String> blacklist = parseSymbols(agent.getSymbolBlacklist());
        BigDecimal minRank = BigDecimal.valueOf(agent.getMinFinalRankScore());

        return candidates.stream()
                .filter(c -> c != null && c.instId() != null)
                .filter(c -> !userClaimedSymbols.contains(c.instId().toUpperCase()))
                .filter(c -> whitelist.isEmpty() || whitelist.contains(c.instId().toUpperCase()) || whitelist.contains(safeUpper(c.baseCurrency())))
                .filter(c -> !blacklist.contains(c.instId().toUpperCase()) && !blacklist.contains(safeUpper(c.baseCurrency())))
                .filter(c -> c.score() >= agent.getMinTotalScore())
                .filter(c -> c.finalRankScore() == null || c.finalRankScore().compareTo(minRank) >= 0)
                .filter(c -> matchesDirection(agent.getDirectionPreference(), c.trendDirection()))
                .limit(TOP_LLM_CANDIDATES)
                .toList();
    }

    private static boolean matchesDirection(TradingAgentDirection pref, DirectionBias bias) {
        if (pref == null || pref == TradingAgentDirection.BOTH) {
            return bias == DirectionBias.BULLISH || bias == DirectionBias.BEARISH;
        }
        if (pref == TradingAgentDirection.LONG_ONLY) {
            return bias == DirectionBias.BULLISH;
        }
        return bias == DirectionBias.BEARISH;
    }

    private String executeReadOnlyTool(
            TradingAgentEntity agent,
            List<ContractCandidate> filtered,
            String toolName,
            String instId
    ) {
        if ("inspect_candidate_detail".equalsIgnoreCase(toolName)) {
            ContractCandidate c = filtered.stream()
                    .filter(item -> instId.equalsIgnoreCase(item.instId()))
                    .findFirst()
                    .orElse(null);
            if (c == null) {
                return "标的 " + instId + " 不在本轮合格候选池中。";
            }
            return "instId=" + c.instId()
                    + ", 24hChg=" + formatNum(c.changePercent24h()) + "%"
                    + ", 5mChg=" + formatNum(c.changePercent5m()) + "%"
                    + ", funding=" + formatNum(c.fundingRate())
                    + ", oiChg=" + formatNum(c.openInterestChange())
                    + ", bidDepth=" + formatNum(c.bidDepthUsdt())
                    + ", askDepth=" + formatNum(c.askDepthUsdt())
                    + ", atr20m=" + formatNum(c.atrPct20m()) + "%"
                    + ", slPct=" + formatNum(c.stopLossPct()) + "%"
                    + ", reasons=" + (c.candidateReasonList() == null ? "[]" : c.candidateReasonList())
                    + ", riskTags=" + (c.riskTagList() == null ? "[]" : c.riskTagList());
        }
        if ("inspect_symbol_history".equalsIgnoreCase(toolName)) {
            if (memoryService == null) {
                return "历史记忆服务未启用。";
            }
            return memoryService.inspectSymbolHistory(agent.getId(), agent.getUserName(), instId);
        }
        return "不支持的只读工具：" + toolName + "（仅支持 inspect_candidate_detail / inspect_symbol_history）。";
    }

    private static String buildSystemPrompt(TradingAgentEntity agent, boolean toolCallingEnabled) {
        String base = agent.getSystemPrompt()
                + "\n\n【强制输出协议】你必须且只能输出严格 JSON 对象，不要包含 Markdown 代码块或多余文字："
                + "\n最终决策格式：{\"action\":\"TRADE\"|\"SKIP\",\"instId\":\"候选中的合约ID或空字符串\",\"reason\":\"简明中文理由(80字以内)\"}";
        if (toolCallingEnabled) {
            base += "\n在最终决策前，你最多可调用 2 次只读查证工具（严禁下单工具），格式："
                    + "{\"action\":\"TOOL_CALL\",\"tool\":\"inspect_candidate_detail\"|\"inspect_symbol_history\",\"instId\":\"候选中的合约ID\",\"reason\":\"查证目的\"}";
        }
        return base + "\n你只能从用户消息提供的候选列表中选择 instId；如果没有高把握机会，必须返回 action=\"SKIP\"。";
    }

    private static String buildCandidateDigestPrompt(
            TradingAgentEntity agent,
            List<ContractCandidate> candidates,
            Map<String, V2SelectionScoreResult> v2Scores,
            String memoryContext
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent=").append(agent.getName())
                .append(", Mode=").append(agent.getRunMode())
                .append(", BudgetUSDT=").append(agent.getAllocatedBudgetUsdt())
                .append(", MaxLeverage=").append(agent.getMaxLeverage()).append("x\n");
        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("【该 Agent 历史复盘与长期记忆】\n").append(memoryContext).append("\n\n");
        }
        sb.append("合格候选列表（按评分排序，最多 3 个）：\n");
        for (int i = 0; i < candidates.size(); i++) {
            ContractCandidate c = candidates.get(i);
            V2SelectionScoreResult v2 = v2Scores.get(c.instId());
            sb.append(i + 1).append(". ")
                    .append(c.instId())
                    .append(" | dir=").append(c.trendDirection())
                    .append(" | score=").append(c.score())
                    .append(" | rank=").append(formatNum(c.finalRankScore()))
                    .append(v2 != null ? " | v2Rank=" + formatNum(v2.rankingScore()) : "")
                    .append(" | price=").append(formatNum(c.lastPrice()))
                    .append(" | sl=").append(formatNum(c.stopLossPrice()))
                    .append(" | tp=").append(formatNum(c.takeProfitPrice()))
                    .append(" | rr=").append(formatNum(c.riskRewardRatio()))
                    .append(" | spreadBps=").append(formatNum(c.spreadBps()))
                    .append(" | signal=").append(c.signalType())
                    .append("\n");
        }
        sb.append("请基于上述客观数据做出本轮唯一 JSON 决策。");
        return sb.toString();
    }

    private AgentRunEntity persistRun(
            TradingAgentEntity agent,
            String scanId,
            String decision,
            String selectedInstId,
            String selectedDirection,
            String llmModel,
            String summaryReason,
            String errorCode,
            List<AgentRunStep> steps,
            long elapsedMs
    ) {
        Instant now = Instant.now();
        AgentRunEntity run = new AgentRunEntity();
        run.setAgentId(agent.getId());
        run.setUserName(agent.getUserName());
        run.setAgentVersionNo(agent.getVersionNo());
        run.setScanRunId(scanId);
        run.setRunMode(agent.getRunMode().name());
        run.setDecision(decision);
        run.setSelectedInstId(selectedInstId);
        run.setSelectedDirection(selectedDirection);
        run.setLlmModel(llmModel);
        run.setSummaryReason(truncate(summaryReason, 500));
        run.setElapsedMs(elapsedMs);
        run.setCreatedAt(now);
        try {
            run.setStepsJson(objectMapper.writeValueAsString(steps));
        } catch (Exception ex) {
            run.setStepsJson("[]");
        }
        AgentRunEntity savedRun = runRepository.save(run);

        agent.setLastDecision(decision);
        agent.setLastSummary(truncate(summaryReason, 500));
        agent.setLastErrorCode(errorCode);
        agent.setLastRunAt(now);
        agent.setUpdatedAt(now);
        agentRepository.save(agent);

        return savedRun;
    }

    private static Set<String> parseSymbols(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    private static String safeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String formatNum(BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) return "";
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private static long elapsedMs(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
