package com.example.quant.agent.platform;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.ai.AiAnalysisService;
import com.example.quant.ai.LlmRuntimeConfig;
import com.example.quant.ai.UserLlmProfileService;
import com.example.quant.auth.AuthUserContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentMemoryService {
    private static final int MAX_MEMORIES_PER_AGENT = 50;
    private static final int MAX_PROMPT_MEMORIES = 8;
    private static final Set<String> VALID_TYPES = Set.of("LESSON", "USER_RULE", "SYMBOL_NOTE", "MARKET_REGIME");

    private final AgentMemoryRepository memoryRepository;
    private final AgentRunRepository runRepository;
    private final TradingAgentService agentService;
    private final UserLlmProfileService llmProfileService;
    private final AiAnalysisService aiAnalysisService;

    public AgentMemoryService(
            AgentMemoryRepository memoryRepository,
            AgentRunRepository runRepository,
            TradingAgentService agentService,
            UserLlmProfileService llmProfileService,
            AiAnalysisService aiAnalysisService
    ) {
        this.memoryRepository = memoryRepository;
        this.runRepository = runRepository;
        this.agentService = agentService;
        this.llmProfileService = llmProfileService;
        this.aiAnalysisService = aiAnalysisService;
    }

    @Transactional(readOnly = true)
    public List<AgentMemoryView> listForCurrentUser(Long agentId) {
        return listForUser(currentUsername(), agentId);
    }

    @Transactional(readOnly = true)
    public List<AgentMemoryView> listForUser(String username, Long agentId) {
        String owner = normalizeUsername(username);
        agentService.requireOwned(owner, agentId);
        return memoryRepository.findByAgentIdAndUserNameOrderByPinnedDescUpdatedAtDesc(agentId, owner).stream()
                .map(AgentMemoryView::from)
                .toList();
    }

    @Transactional
    public AgentMemoryView addForCurrentUser(Long agentId, SaveAgentMemoryRequest request) {
        return addForUser(currentUsername(), agentId, request);
    }

    @Transactional
    public AgentMemoryView addForUser(String username, Long agentId, SaveAgentMemoryRequest request) {
        if (request == null || request.content() == null || request.content().trim().isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String owner = normalizeUsername(username);
        agentService.requireOwned(owner, agentId);
        List<AgentMemoryEntity> existing = memoryRepository.findByAgentIdAndUserNameOrderByPinnedDescUpdatedAtDesc(agentId, owner);
        if (existing.size() >= MAX_MEMORIES_PER_AGENT) {
            throw new IllegalArgumentException("单个 Agent 最多保留 " + MAX_MEMORIES_PER_AGENT + " 条记忆，请先清理旧条目");
        }
        Instant now = Instant.now();
        AgentMemoryEntity entity = new AgentMemoryEntity();
        entity.setAgentId(agentId);
        entity.setUserName(owner);
        entity.setMemoryType(normalizeType(request.memoryType()));
        entity.setInstId(normalizeInstId(request.instId()));
        entity.setContent(truncate(request.content().trim(), 900));
        entity.setPinned(Boolean.TRUE.equals(request.pinned()));
        entity.setEnabled(true);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return AgentMemoryView.from(memoryRepository.save(entity));
    }

    @Transactional
    public AgentMemoryView togglePinForCurrentUser(Long agentId, Long memoryId) {
        String owner = currentUsername();
        agentService.requireOwned(owner, agentId);
        AgentMemoryEntity entity = memoryRepository.findByIdAndAgentIdAndUserName(memoryId, agentId, owner)
                .orElseThrow(() -> new IllegalArgumentException("记忆条目不存在"));
        entity.setPinned(!entity.isPinned());
        entity.setUpdatedAt(Instant.now());
        return AgentMemoryView.from(memoryRepository.save(entity));
    }

    @Transactional
    public void deleteForCurrentUser(Long agentId, Long memoryId) {
        String owner = currentUsername();
        agentService.requireOwned(owner, agentId);
        AgentMemoryEntity entity = memoryRepository.findByIdAndAgentIdAndUserName(memoryId, agentId, owner)
                .orElseThrow(() -> new IllegalArgumentException("记忆条目不存在"));
        memoryRepository.delete(entity);
    }

    @Transactional(readOnly = true)
    public String buildMemoryContext(Long agentId, String username) {
        List<AgentMemoryEntity> memories = memoryRepository
                .findByAgentIdAndUserNameAndEnabledTrueOrderByPinnedDescUpdatedAtDesc(agentId, normalizeUsername(username));
        if (memories.isEmpty()) {
            return "";
        }
        return memories.stream()
                .limit(MAX_PROMPT_MEMORIES)
                .map(m -> "- [" + m.getMemoryType()
                        + (m.getInstId() != null ? " / " + m.getInstId() : "")
                        + (m.isPinned() ? " / 置顶" : "") + "] " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    @Transactional(readOnly = true)
    public String inspectSymbolHistory(Long agentId, String username, String instId) {
        String owner = normalizeUsername(username);
        String symbol = normalizeInstId(instId);
        List<AgentRunEntity> recentRuns = runRepository.findByAgentIdAndUserNameOrderByCreatedAtDesc(
                agentId, owner, PageRequest.of(0, 20));
        String runSummary = recentRuns.stream()
                .filter(r -> symbol != null && symbol.equalsIgnoreCase(r.getSelectedInstId()))
                .limit(5)
                .map(r -> r.getCreatedAt() + " " + r.getDecision() + " (" + r.getSummaryReason() + ")")
                .collect(Collectors.joining("; "));
        String symbolMemories = memoryRepository
                .findByAgentIdAndUserNameAndEnabledTrueOrderByPinnedDescUpdatedAtDesc(agentId, owner).stream()
                .filter(m -> symbol != null && symbol.equalsIgnoreCase(m.getInstId()))
                .limit(3)
                .map(AgentMemoryEntity::getContent)
                .collect(Collectors.joining("; "));
        if (runSummary.isEmpty() && symbolMemories.isEmpty()) {
            return symbol + " 暂无该 Agent 的历史运行记录或专属记忆。";
        }
        return "历史记录: " + (runSummary.isEmpty() ? "无" : runSummary)
                + " | 专属记忆: " + (symbolMemories.isEmpty() ? "无" : symbolMemories);
    }

    /**
     * Async/on-demand Reflection Sub-Agent: reads recent decision runs and distills
     * 1-3 concrete, reusable lessons into AgentMemory using the user's BYOK LLM.
     */
    @Transactional
    public List<AgentMemoryView> reflectNowForCurrentUser(Long agentId) {
        return reflectNowForUser(currentUsername(), agentId);
    }

    @Transactional
    public List<AgentMemoryView> reflectNowForUser(String username, Long agentId) {
        String owner = normalizeUsername(username);
        TradingAgentEntity agent = agentService.requireOwned(owner, agentId);
        LlmRuntimeConfig runtime = llmProfileService.resolveRuntimeConfig(owner, agent.getLlmProfileId())
                .orElseThrow(() -> new IllegalArgumentException("请先配置个人大模型 API Key（BYOK）后再运行复盘反思"));

        List<AgentRunEntity> recentRuns = runRepository.findByAgentIdAndUserNameOrderByCreatedAtDesc(
                agentId, owner, PageRequest.of(0, 15));
        if (recentRuns.isEmpty()) {
            throw new IllegalArgumentException("该 Agent 暂无历史运行记录，请先试跑或运行至少一轮后再复盘");
        }

        StringBuilder digest = new StringBuilder();
        digest.append("Agent=").append(agent.getName()).append(", Template=").append(agent.getTemplateCode()).append("\n");
        digest.append("最近 ").append(recentRuns.size()).append(" 轮决策记录：\n");
        for (AgentRunEntity r : recentRuns) {
            digest.append("- ").append(r.getDecision())
                    .append(" | inst=").append(r.getSelectedInstId() == null ? "-" : r.getSelectedInstId())
                    .append(" | reason=").append(r.getSummaryReason() == null ? "" : r.getSummaryReason())
                    .append("\n");
        }
        String existingMemory = buildMemoryContext(agentId, owner);
        if (!existingMemory.isEmpty()) {
            digest.append("\n已有记忆（请避免重复）：\n").append(existingMemory);
        }

        String systemPrompt = "你是一名量化交易复盘反思专家。请根据该 Agent 的近期决策与拦截记录，提炼 1-3 条简短、可执行、不与已有记忆重复的改进经验。"
                + "必须严格输出 JSON：{\"lessons\":[{\"memoryType\":\"LESSON\"|\"SYMBOL_NOTE\"|\"MARKET_REGIME\",\"instId\":\"可选合约ID或空\",\"content\":\"120字以内的具体经验\"}]}";

        JsonNode response = aiAnalysisService.completeJson(runtime, systemPrompt, digest.toString());
        JsonNode lessonsNode = response.path("lessons");
        List<AgentMemoryView> created = new ArrayList<>();
        if (lessonsNode.isArray()) {
            int count = 0;
            for (JsonNode item : lessonsNode) {
                if (count >= 3) break;
                String content = item.path("content").asText("").trim();
                if (content.isEmpty()) continue;
                String type = item.path("memoryType").asText("LESSON");
                String instId = item.path("instId").asText("");
                created.add(addForUser(owner, agentId, new SaveAgentMemoryRequest(type, instId, content, false)));
                count++;
            }
        }
        return created;
    }

    private static String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) return "LESSON";
        String upper = raw.trim().toUpperCase();
        return VALID_TYPES.contains(upper) ? upper : "LESSON";
    }

    private static String normalizeInstId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toUpperCase();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
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
