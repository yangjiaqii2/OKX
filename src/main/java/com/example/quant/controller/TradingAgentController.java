package com.example.quant.controller;

import com.example.quant.account.OkxCredentialStore;
import com.example.quant.agent.platform.SaveTradingAgentRequest;
import com.example.quant.agent.platform.TradingAgentOrchestrator;
import com.example.quant.agent.platform.TradingAgentService;
import com.example.quant.auth.AdminOnly;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.crypto.UserPrivateWebSocketPool;
import com.example.quant.system.SystemControlService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/agents")
public class TradingAgentController {
    private final TradingAgentService agentService;
    private final TradingAgentOrchestrator orchestrator;
    private final UserPrivateWebSocketPool wsPool;
    private final SystemControlService systemControlService;

    @Autowired
    public TradingAgentController(
            TradingAgentService agentService,
            TradingAgentOrchestrator orchestrator,
            UserPrivateWebSocketPool wsPool,
            SystemControlService systemControlService
    ) {
        this.agentService = agentService;
        this.orchestrator = orchestrator;
        this.wsPool = wsPool;
        this.systemControlService = systemControlService;
    }

    @GetMapping("/templates")
    public Object templates() {
        return agentService.listTemplates();
    }

    @GetMapping("/ws-status")
    public Object wsStatus() {
        String user = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        return wsPool.statusForUser(user);
    }

    @GetMapping
    public Object list() {
        return agentService.listForCurrentUser();
    }

    @GetMapping("/{id}")
    public Object detail(@PathVariable Long id) {
        return agentService.getForCurrentUser(id);
    }

    @PostMapping
    public Object save(@RequestBody SaveTradingAgentRequest request) {
        return agentService.saveForCurrentUser(request);
    }

    @PostMapping("/{id}/start")
    public Object start(@PathVariable Long id) {
        return agentService.startForCurrentUser(id);
    }

    @PostMapping("/{id}/pause")
    public Object pause(@PathVariable Long id) {
        return agentService.pauseForCurrentUser(id);
    }

    @PostMapping("/{id}/run-once")
    public Object runOnce(@PathVariable Long id) {
        return orchestrator.runSingleAgentNow(id);
    }

    @GetMapping("/{id}/runs")
    public Object runs(@PathVariable Long id, @RequestParam(defaultValue = "20") int limit) {
        return agentService.listRunsForCurrentUser(id, limit);
    }

    @DeleteMapping("/{id}")
    public Object delete(@PathVariable Long id) {
        agentService.deleteForCurrentUser(id);
        return agentService.listForCurrentUser();
    }

    @AdminOnly
    @GetMapping("/admin/overview")
    public Object adminOverview() {
        return agentService.adminOverview(
                systemControlService.emergencyStopEnabled(),
                wsPool.poolSnapshot()
        );
    }

    @AdminOnly
    @PostMapping("/admin/pause-all")
    public Object pauseAllRunningAgents() {
        int pausedCount = agentService.pauseAllRunningAgents();
        systemControlService.emergencyStop();
        return Map.of(
                "pausedCount", pausedCount,
                "overview", agentService.adminOverview(
                        systemControlService.emergencyStopEnabled(),
                        wsPool.poolSnapshot()
                )
        );
    }
}
