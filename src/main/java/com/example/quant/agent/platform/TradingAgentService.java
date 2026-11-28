package com.example.quant.agent.platform;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.ai.UserLlmProfileService;
import com.example.quant.auth.AuthUserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingAgentService {
    public static final int MAX_AGENTS_PER_USER = 5;
    public static final int PLATFORM_HARD_MAX_LEVERAGE = 10;
    public static final int PLATFORM_HARD_MAX_POSITIONS = 3;
    public static final BigDecimal PLATFORM_HARD_MAX_SINGLE_LOSS_PCT = new BigDecimal("2.0");
    public static final BigDecimal PLATFORM_HARD_MAX_DAILY_LOSS_PCT = new BigDecimal("5.0");
    public static final BigDecimal PLATFORM_MIN_BUDGET_USDT = new BigDecimal("10");

    private static final TypeReference<List<AgentRunStep>> STEP_LIST_TYPE = new TypeReference<>() {};

    private final TradingAgentRepository agentRepository;
    private final AgentRunRepository runRepository;
    private final UserLlmProfileService llmProfileService;
    private final OkxCredentialStore okxCredentialStore;
    private final ObjectMapper objectMapper;

    public TradingAgentService(
            TradingAgentRepository agentRepository,
            AgentRunRepository runRepository,
            UserLlmProfileService llmProfileService,
            OkxCredentialStore okxCredentialStore,
            ObjectMapper objectMapper
    ) {
        this.agentRepository = agentRepository;
        this.runRepository = runRepository;
        this.llmProfileService = llmProfileService;
        this.okxCredentialStore = okxCredentialStore;
        this.objectMapper = objectMapper;
    }

    public List<AgentTemplateView> listTemplates() {
        return Arrays.stream(TradingAgentTemplate.values())
                .map(AgentTemplateView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TradingAgentView> listForCurrentUser() {
        return listForUser(currentUsername());
    }

    @Transactional(readOnly = true)
    public List<TradingAgentView> listForUser(String username) {
        return agentRepository.findByUserNameOrderByUpdatedAtDesc(normalizeUsername(username)).stream()
                .map(TradingAgentView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public TradingAgentView getForCurrentUser(Long id) {
        return TradingAgentView.from(requireOwned(currentUsername(), id));
    }

    @Transactional
    public TradingAgentView saveForCurrentUser(SaveTradingAgentRequest request) {
        return saveForUser(currentUsername(), request);
    }

    @Transactional
    public TradingAgentView saveForUser(String username, SaveTradingAgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent 配置不能为空");
        }
        String owner = normalizeUsername(username);
        String name = requireText(request.name(), "Agent 名称不能为空", 128);
        TradingAgentTemplate template = TradingAgentTemplate.parse(request.templateCode());
        TradingAgentRunMode runMode = parseRunMode(request.runMode());
        TradingAgentDirection direction = parseDirection(request.directionPreference(), template.defaultDirection());

        BigDecimal budget = request.allocatedBudgetUsdt() != null ? request.allocatedBudgetUsdt() : template.defaultBudgetUsdt();
        if (budget.compareTo(PLATFORM_MIN_BUDGET_USDT) < 0) {
            throw new IllegalArgumentException("分配预算不能低于 10 USDT");
        }
        int maxPositions = clamp(
                request.maxOpenPositions() != null ? request.maxOpenPositions() : template.defaultMaxOpenPositions(),
                1, PLATFORM_HARD_MAX_POSITIONS
        );
        int maxLeverage = clamp(
                request.maxLeverage() != null ? request.maxLeverage() : template.defaultMaxLeverage(),
                1, PLATFORM_HARD_MAX_LEVERAGE
        );
        int minTotalScore = clamp(
                request.minTotalScore() != null ? request.minTotalScore() : template.defaultMinTotalScore(),
                40, 95
        );
        int minFinalRankScore = clamp(
                request.minFinalRankScore() != null ? request.minFinalRankScore() : template.defaultMinFinalRankScore(),
                40, 95
        );
        BigDecimal maxSingleLoss = clampDecimal(
                request.maxSingleLossPct() != null ? request.maxSingleLossPct() : template.defaultMaxSingleLossPct(),
                new BigDecimal("0.2"), PLATFORM_HARD_MAX_SINGLE_LOSS_PCT
        );
        BigDecimal maxDailyLoss = clampDecimal(
                request.maxDailyLossPct() != null ? request.maxDailyLossPct() : template.defaultMaxDailyLossPct(),
                new BigDecimal("0.5"), PLATFORM_HARD_MAX_DAILY_LOSS_PCT
        );

        if (request.llmProfileId() != null && llmProfileService.resolveRuntimeConfig(owner, request.llmProfileId()).isEmpty()) {
            throw new IllegalArgumentException("所选大模型 API 配置不存在");
        }

        Instant now = Instant.now();
        TradingAgentEntity entity;
        if (request.id() != null) {
            entity = requireOwned(owner, request.id());
            entity.setVersionNo(entity.getVersionNo() + 1);
        } else {
            if (agentRepository.countByUserName(owner) >= MAX_AGENTS_PER_USER) {
                throw new IllegalArgumentException("每个账号最多创建 " + MAX_AGENTS_PER_USER + " 个交易 Agent");
            }
            entity = new TradingAgentEntity();
            entity.setUserName(owner);
            entity.setStatus(TradingAgentStatus.PAUSED);
            entity.setVersionNo(1);
            entity.setCreatedAt(now);
        }

        entity.setName(name);
        entity.setTemplateCode(template.name());
        entity.setRunMode(runMode);
        entity.setLlmProfileId(request.llmProfileId());
        entity.setAllocatedBudgetUsdt(budget);
        entity.setMaxOpenPositions(maxPositions);
        entity.setMaxLeverage(maxLeverage);
        entity.setMinTotalScore(minTotalScore);
        entity.setMinFinalRankScore(minFinalRankScore);
        entity.setMaxSingleLossPct(maxSingleLoss);
        entity.setMaxDailyLossPct(maxDailyLoss);
        entity.setDirectionPreference(direction);
        entity.setSymbolWhitelist(normalizeSymbolList(request.symbolWhitelist()));
        entity.setSymbolBlacklist(normalizeSymbolList(request.symbolBlacklist()));
        String prompt = request.systemPrompt() == null || request.systemPrompt().isBlank()
                ? template.defaultSystemPrompt()
                : request.systemPrompt().trim();
        entity.setSystemPrompt(prompt);
        entity.setToolCallingEnabled(request.toolCallingEnabled() == null || request.toolCallingEnabled());
        entity.setEvalIntervalMinutes(clamp(
                request.evalIntervalMinutes() != null ? request.evalIntervalMinutes() : 5, 1, 1440
        ));
        entity.setUpdatedAt(now);

        return TradingAgentView.from(agentRepository.save(entity));
    }

    @Transactional
    public TradingAgentView startForCurrentUser(Long id) {
        return startForUser(currentUsername(), id);
    }

    @Transactional
    public TradingAgentView startForUser(String username, Long id) {
        String owner = normalizeUsername(username);
        TradingAgentEntity entity = requireOwned(owner, id);
        if (llmProfileService.resolveRuntimeConfig(owner, entity.getLlmProfileId()).isEmpty()) {
            throw new IllegalArgumentException("请先在「大模型 API」页面配置并启用你自己的 API Key，再启动 Agent");
        }
        if (entity.getRunMode() != TradingAgentRunMode.PAPER && okxCredentialStore.findActive(owner).isEmpty()) {
            throw new IllegalArgumentException("非 PAPER 模拟模式需先在「账号绑定」页面绑定你自己的 OKX 密钥");
        }
        entity.setStatus(TradingAgentStatus.RUNNING);
        entity.setLastErrorCode(null);
        entity.setUpdatedAt(Instant.now());
        return TradingAgentView.from(agentRepository.save(entity));
    }

    @Transactional
    public TradingAgentView pauseForCurrentUser(Long id) {
        return pauseForUser(currentUsername(), id);
    }

    @Transactional
    public TradingAgentView pauseForUser(String username, Long id) {
        String owner = normalizeUsername(username);
        TradingAgentEntity entity = requireOwned(owner, id);
        entity.setStatus(TradingAgentStatus.PAUSED);
        entity.setUpdatedAt(Instant.now());
        return TradingAgentView.from(agentRepository.save(entity));
    }

    @Transactional
    public void deleteForCurrentUser(Long id) {
        deleteForUser(currentUsername(), id);
    }

    @Transactional
    public void deleteForUser(String username, Long id) {
        TradingAgentEntity entity = requireOwned(normalizeUsername(username), id);
        agentRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public AdminAgentOverview adminOverview(
            boolean emergencyStopped,
            List<com.example.quant.crypto.UserPrivateWebSocketPool.UserWsStatus> wsSessions
    ) {
        List<TradingAgentEntity> all = agentRepository.findAll();
        int running = (int) all.stream().filter(a -> a.getStatus() == TradingAgentStatus.RUNNING).count();
        int activeUsers = (int) all.stream()
                .filter(a -> a.getStatus() == TradingAgentStatus.RUNNING)
                .map(TradingAgentEntity::getUserName)
                .distinct()
                .count();
        List<AdminAgentOverview.AdminAgentSummary> summaries = all.stream()
                .map(AdminAgentOverview.AdminAgentSummary::from)
                .toList();
        return new AdminAgentOverview(
                all.size(),
                running,
                activeUsers,
                emergencyStopped,
                wsSessions == null ? List.of() : wsSessions,
                summaries
        );
    }

    @Transactional
    public int pauseAllRunningAgents() {
        List<TradingAgentEntity> running = agentRepository.findByStatusOrderByUpdatedAtAsc(TradingAgentStatus.RUNNING);
        Instant now = Instant.now();
        for (TradingAgentEntity agent : running) {
            agent.setStatus(TradingAgentStatus.PAUSED);
            agent.setLastSummary("管理员触发平台全局紧急暂停");
            agent.setUpdatedAt(now);
            agentRepository.save(agent);
        }
        return running.size();
    }

    @Transactional(readOnly = true)
    public List<AgentRunView> listRunsForCurrentUser(Long agentId, int limit) {
        return listRunsForUser(currentUsername(), agentId, limit);
    }

    @Transactional(readOnly = true)
    public List<AgentRunView> listRunsForUser(String username, Long agentId, int limit) {
        String owner = normalizeUsername(username);
        requireOwned(owner, agentId);
        int safeLimit = clamp(limit, 1, 100);
        return runRepository.findByAgentIdAndUserNameOrderByCreatedAtDesc(agentId, owner, PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toRunView)
                .toList();
    }

    public TradingAgentEntity requireOwned(String username, Long id) {
        return agentRepository.findByIdAndUserName(id, normalizeUsername(username))
                .orElseThrow(() -> new IllegalArgumentException("交易 Agent 不存在"));
    }

    public AgentRunView toRunView(AgentRunEntity entity) {
        List<AgentRunStep> steps = List.of();
        if (entity.getStepsJson() != null && !entity.getStepsJson().isBlank()) {
            try {
                steps = objectMapper.readValue(entity.getStepsJson(), STEP_LIST_TYPE);
            } catch (Exception ignored) {
                steps = List.of();
            }
        }
        return new AgentRunView(
                entity.getId(),
                entity.getAgentId(),
                entity.getAgentVersionNo(),
                entity.getScanRunId(),
                entity.getRunMode(),
                entity.getDecision(),
                entity.getSelectedInstId(),
                entity.getSelectedDirection(),
                entity.getLlmModel(),
                entity.getSummaryReason(),
                steps,
                entity.getElapsedMs(),
                entity.getCreatedAt()
        );
    }

    private static TradingAgentRunMode parseRunMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return TradingAgentRunMode.PAPER;
        }
        try {
            return TradingAgentRunMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的运行模式: " + raw);
        }
    }

    private static TradingAgentDirection parseDirection(String raw, TradingAgentDirection fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return TradingAgentDirection.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的方向偏好: " + raw);
        }
    }

    private static String normalizeSymbolList(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return Arrays.stream(raw.split("[,\\s;]+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }

    private static String requireText(String value, String message, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(message + "（长度超限）");
        }
        return trimmed;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BigDecimal clampDecimal(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) return min;
        if (value.compareTo(max) > 0) return max;
        return value;
    }

    private static String currentUsername() {
        return AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return OkxCredentialStore.SYSTEM_USER;
        }
        return username.trim();
    }
}
