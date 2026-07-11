package com.example.quant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.PositionSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.agent.execution.AutoTradeRecordService;
import com.example.quant.agent.execution.AutoTradeService;
import com.example.quant.agent.execution.AutoTradeService.AutoTradeResult;
import com.example.quant.agent.execution.TradeOrderRecordService;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.crypto.ContractAnalysisService;
import com.example.quant.crypto.dto.ContractCandidate;
import com.example.quant.order.PendingOrderService;
import com.example.quant.system.SystemControlService;
import com.example.quant.tradeplan.TradePlanService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ContractMarketScanTaskTest {

    @Test
    void successfulSlowScanStillRunsAutoTrade() {
        AgentProperties properties = new AgentProperties();
        properties.timeout().setScanRoundTimeoutSeconds(-1);
        ContractAnalysisService analysisService = mock(ContractAnalysisService.class);
        AutoTradeService autoTradeService = mock(AutoTradeService.class);
        when(analysisService.refreshCandidates()).thenReturn(List.of());
        when(autoTradeService.evaluateAndExecute(List.of())).thenReturn(new AutoTradeResult(
                "SKIPPED", null, null, null, null, null, null, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, "no_candidates", Instant.now()
        ));

        ScanTaskLog result = new ContractMarketScanTask(
                analysisService,
                autoTradeService,
                properties
        ).runOnce();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("SCAN_SLOW");
        verify(autoTradeService).evaluateAndExecute(List.of());
    }

    @Test
    void skipsSecondScanWhenPreviousScanIsStillRunning() throws Exception {
        BlockingContractAnalysisService analysisService = new BlockingContractAnalysisService();
        ContractMarketScanTask task = new ContractMarketScanTask(
                analysisService,
                autoTradeService(),
                new AgentProperties()
        );
        var executor = Executors.newSingleThreadExecutor();

        executor.submit(task::runOnce);
        assertThat(analysisService.entered.await(2, TimeUnit.SECONDS)).isTrue();

        ScanTaskLog skipped = task.runOnce();

        assertThat(skipped.status()).isEqualTo("SKIPPED");
        assertThat(skipped.message()).contains("SCAN_ALREADY_RUNNING");
        analysisService.release.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    private static AutoTradeService autoTradeService() {
        return new AutoTradeService(
                new AgentProperties(),
                new SystemControlService(new TradingProperties("SEMI_AUTO", true, true, 120, false)),
                new TradeOrderRecordService(null, null),
                new AutoTradeRecordService(null),
                new TradePlanService(null),
                new PendingOrderService(120),
                null,
                new AccountSnapshotService(null) {
                    @Override
                    public AccountSummary summary() {
                        return new AccountSummary(BigDecimal.ZERO, BigDecimal.ZERO, "TEST", "");
                    }
                },
                new PositionSnapshotService(null) {
                    @Override
                    public List<com.example.quant.account.dto.PositionSummary> positions() {
                        return List.of();
                    }
                }
        );
    }

    private static final class BlockingContractAnalysisService extends ContractAnalysisService {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingContractAnalysisService() {
            super(null, null, null);
        }

        @Override
        public List<ContractCandidate> refreshCandidates() {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }
}
