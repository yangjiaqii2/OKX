package com.example.quant.controller;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.ExchangePositionRepository;
import com.example.quant.account.OkxCredentialStore;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.execution.TradeOrderRepository;
import com.example.quant.auth.AuthUserContext;
import com.example.quant.account.dto.OkxAccountVerificationResult;
import com.example.quant.config.TradingProperties;
import com.example.quant.config.AgentProperties;
import com.example.quant.crypto.OkxWebSocketService;
import com.example.quant.ledger.AccountLedgerSyncStateEntity;
import com.example.quant.ledger.AccountLedgerSyncStateRepository;
import com.example.quant.system.AutoTradeRiskMode;
import com.example.quant.system.AutoTradeBudgetMode;
import com.example.quant.system.FxRateService;
import com.example.quant.system.SystemControlService;
import com.example.quant.system.TradingReadinessService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/system")
public class SystemControlController {
    private final SystemControlService systemControlService;
    private final AccountSnapshotService accountSnapshotService;
    private final FxRateService fxRateService;
    private final TradingProperties tradingProperties;
    private final OkxWebSocketService okxWebSocketService;
    private AgentProperties agentProperties = new AgentProperties();
    private TradingReadinessService tradingReadinessService;
    private TradeOrderRepository tradeOrders;
    private ExchangePositionRepository exchangePositions;
    private AccountLedgerSyncStateRepository ledgerStates;

    public SystemControlController(SystemControlService systemControlService, AccountSnapshotService accountSnapshotService) {
        this(systemControlService, accountSnapshotService, new FxRateService(null),
                new TradingProperties("UNKNOWN", true, false, 120, false), new OkxWebSocketService());
    }

    public SystemControlController(SystemControlService systemControlService, AccountSnapshotService accountSnapshotService,
                                   FxRateService fxRateService) {
        this(systemControlService, accountSnapshotService, fxRateService,
                new TradingProperties("UNKNOWN", true, false, 120, false), new OkxWebSocketService());
    }

    @Autowired
    public SystemControlController(SystemControlService systemControlService, AccountSnapshotService accountSnapshotService,
                                   FxRateService fxRateService, TradingProperties tradingProperties,
                                   OkxWebSocketService okxWebSocketService) {
        this.systemControlService = systemControlService;
        this.accountSnapshotService = accountSnapshotService;
        this.fxRateService = fxRateService == null ? new FxRateService(null) : fxRateService;
        this.tradingProperties = tradingProperties;
        this.okxWebSocketService = okxWebSocketService;
    }

    @GetMapping("/status")
    public Object status() {
        return systemControlService.status();
    }

    @GetMapping("/runtime-status")
    public RuntimeStatus runtimeStatus() {
        AccountSummary account = accountSnapshotService.summary();
        SystemControlService.SystemStatus system = systemControlService.status();
        com.example.quant.config.TradingMode mode = tradingProperties == null
                ? com.example.quant.config.TradingMode.DISABLED : tradingProperties.mode();
        boolean autoTradeConfigured = agentProperties != null && agentProperties.autoTrade().enabled();
        boolean shadowMode = system.autoTradeEnabled() && system.autoTradeRiskMode() == AutoTradeRiskMode.NO_RISK;
        String strategyEngine = system.emergencyStop()
                ? "EMERGENCY_STOP"
                : system.autoTradeEnabled() && !autoTradeConfigured
                ? "AUTO_DISABLED_BY_CONFIG"
                : shadowMode ? "SHADOW_RUNNING"
                : system.autoTradeEnabled() ? "AUTO_RUNNING" : "MONITORING_ONLY";
        String tradingEngine = !mode.writesEnabled()
                ? "DISABLED"
                : tradingReadinessService != null && !tradingReadinessService.ready()
                ? tradingReadinessService.snapshot().state().name()
                : system.emergencyStop() ? "EMERGENCY_STOP"
                : shadowMode ? "SHADOW_ONLY"
                : system.autoTradeEnabled() && autoTradeConfigured ? "AUTO_ENABLED" : "MANUAL_CONFIRM";
        String owner = AuthUserContext.currentUsername().orElse(OkxCredentialStore.SYSTEM_USER);
        return new RuntimeStatus(
                mode.name(),
                restStatus(account == null ? null : account.mode()),
                okxWebSocketService.publicStatus(),
                okxWebSocketService.privateStatus(),
                okxWebSocketService.privateReason(),
                strategyEngine,
                tradingEngine,
                !mode.writesEnabled() ? "TRADING_MODE_DISABLED"
                        : system.autoTradeEnabled() && !autoTradeConfigured ? "AUTO_TRADE_DISABLED_BY_CONFIG"
                        : shadowMode ? "NO_RISK_MODE_DOES_NOT_SUBMIT_ORDERS"
                        : tradingReadinessService == null ? "READINESS_NOT_CONFIGURED"
                        : tradingReadinessService.snapshot().reason(),
                unknownOrderCount(owner),
                unmanagedPositionCount(owner),
                ledgerStatus(owner),
                Instant.now()
        );
    }

    @Autowired(required = false)
    void configureTradingReadiness(TradingReadinessService tradingReadinessService) {
        this.tradingReadinessService = tradingReadinessService;
    }

    @Autowired(required = false)
    void configureObservability(TradeOrderRepository tradeOrders,
                                ExchangePositionRepository exchangePositions,
                                AccountLedgerSyncStateRepository ledgerStates) {
        this.tradeOrders = tradeOrders;
        this.exchangePositions = exchangePositions;
        this.ledgerStates = ledgerStates;
    }

    @Autowired(required = false)
    void configureAgentProperties(AgentProperties agentProperties) {
        if (agentProperties != null) {
            this.agentProperties = agentProperties;
        }
    }

    @PostMapping("/emergency-stop")
    public Object emergencyStop() {
        return systemControlService.emergencyStop();
    }

    @PostMapping("/resume")
    public Object resume() {
        return systemControlService.resume();
    }

    @PostMapping("/auto-trade/enable")
    public Object enableAutoTrade(@RequestParam(required = false) BigDecimal marginUsdt,
                                  @RequestParam(required = false) BigDecimal marginPct,
                                  @RequestParam(required = false, defaultValue = "FIXED_USDT") AutoTradeBudgetMode budgetMode,
                                  @RequestParam(required = false) AutoTradeRiskMode riskMode,
                                  @RequestParam(required = false) Integer noRiskMinScore,
                                  @RequestParam(required = false) Integer minLeverage) {
        verifyOkxAccountBeforeEnable();
        if (budgetMode == AutoTradeBudgetMode.AVAILABLE_BALANCE_PERCENT) {
            return systemControlService.enableAutoTradePercent(marginPct, riskMode == null ? AutoTradeRiskMode.STRICT : riskMode,
                    noRiskMinScore, minLeverage);
        }
        if (riskMode == null) {
            return systemControlService.enableAutoTrade(marginUsdt);
        }
        return systemControlService.enableAutoTrade(marginUsdt, riskMode, noRiskMinScore, minLeverage);
    }

    public Object enableAutoTrade(BigDecimal marginUsdt, AutoTradeRiskMode riskMode,
                                  Integer noRiskMinScore, Integer minLeverage) {
        verifyOkxAccountBeforeEnable();
        return systemControlService.enableAutoTrade(marginUsdt, riskMode, noRiskMinScore, minLeverage);
    }

    @PostMapping("/auto-trade/disable")
    public Object disableAutoTrade() {
        return systemControlService.disableAutoTrade();
    }

    @GetMapping("/fx-rate")
    public Object fxRate(@RequestParam(defaultValue = "USD") String base,
                         @RequestParam(defaultValue = "CNY") String quote) {
        return fxRateService.rate(base, quote);
    }

    private void verifyOkxAccountBeforeEnable() {
        OkxAccountVerificationResult verification = accountSnapshotService.verifyOkx();
        if (verification == null || !verification.ok()) {
            String mode = verification == null ? "UNKNOWN" : verification.mode();
            String message = verification == null ? "OKX账户验证失败。" : verification.message();
            throw new IllegalStateException("开启自动交易前必须通过OKX Key验证：mode=" + mode + "，" + message);
        }
    }

    private static String restStatus(String mode) {
        if ("OKX_REAL".equals(mode) || "OKX_DEMO".equals(mode) || "OKX_EMPTY".equals(mode)) {
            return "CONNECTED";
        }
        if ("OKX_UNBOUND".equals(mode)) {
            return "UNBOUND";
        }
        return "ERROR";
    }

    private long unknownOrderCount(String owner) {
        return tradeOrders == null ? -1 : tradeOrders.countByUserNameAndStatusIn(owner,
                List.of("UNKNOWN_SUBMIT_STATUS", "RECONCILING", "CLOSE_SUBMIT_UNKNOWN"));
    }

    private long unmanagedPositionCount(String owner) {
        return exchangePositions == null ? -1
                : exchangePositions.countByUserNameAndOwnership(owner, "UNMANAGED");
    }

    private String ledgerStatus(String owner) {
        return ledgerStates == null ? "NOT_CONFIGURED" : ledgerStates.findById(owner)
                .map(AccountLedgerSyncStateEntity::getStatus).orElse("NOT_SYNCED");
    }

    public record RuntimeStatus(
            String tradingMode,
            String okxRest,
            String publicWebSocket,
            String privateWebSocket,
            String privateWebSocketReason,
            String strategyEngine,
            String tradingEngine,
            String tradingReason,
            long unknownOrders,
            long unmanagedPositions,
            String ledgerSync,
            Instant checkedAt
    ) {
    }
}
