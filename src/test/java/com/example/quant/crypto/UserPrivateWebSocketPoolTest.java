package com.example.quant.crypto;

import com.example.quant.account.OkxAccountBindingService;
import com.example.quant.agent.platform.TradingAgentEntity;
import com.example.quant.agent.platform.TradingAgentRepository;
import com.example.quant.agent.platform.TradingAgentRunMode;
import com.example.quant.agent.platform.TradingAgentStatus;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.TradingMode;
import com.example.quant.config.TradingProperties;
import com.example.quant.okxtrade.OkxOrderEventService;
import com.example.quant.system.OkxRuntimeReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.WebSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserPrivateWebSocketPoolTest {

    @Test
    void connectsOnlyActiveNonPaperUsersAndDisconnectsWhenPaused() {
        TradingAgentRepository agentRepo = mock(TradingAgentRepository.class);
        OkxAccountBindingService binding = mock(OkxAccountBindingService.class);
        OkxSigner signer = mock(OkxSigner.class);
        OkxOrderEventService orderEvents = mock(OkxOrderEventService.class);
        OkxRuntimeReconciliationService reconciliation = mock(OkxRuntimeReconciliationService.class);

        TradingAgentEntity aliceAuto = agent("alice", TradingAgentRunMode.AUTO);
        TradingAgentEntity bobPaper = agent("bob", TradingAgentRunMode.PAPER);
        when(agentRepo.findByStatusOrderByUpdatedAtAsc(TradingAgentStatus.RUNNING))
                .thenReturn(List.of(aliceAuto, bobPaper));
        when(binding.credentials()).thenReturn(Optional.of(
                new OkxAccountBindingService.OkxCredentials("key", "secret", "pass")
        ));

        AtomicInteger connectCalls = new AtomicInteger();
        OkxWebSocketService.Connector fakeConnector = (URI uri, WebSocket.Listener listener) -> {
            connectCalls.incrementAndGet();
            return new CompletableFuture<>();
        };

        TradingProperties tradingProps = new TradingProperties(TradingMode.DEMO, false, 15, false);
        OkxProperties okxProps = new OkxProperties(
                new OkxProperties.Api("https://www.okx.com", "", "", ""),
                new OkxProperties.Websocket("wss://ws.okx.com:8443/ws/v5/public",
                        "wss://ws.okx.com:8443/ws/v5/private", 3000L, 20000L, 5000L)
        );
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T06:00:00Z"), ZoneOffset.UTC);

        UserPrivateWebSocketPool pool = new UserPrivateWebSocketPool(
                tradingProps, okxProps, binding, signer, new ObjectMapper(),
                orderEvents, reconciliation, agentRepo, fakeConnector, clock
        );

        pool.maintainPool();

        assertThat(connectCalls.get()).isEqualTo(1);
        assertThat(pool.poolSnapshot()).hasSize(1);
        assertThat(pool.statusForUser("alice").state()).isEqualTo("CONNECTING");
        assertThat(pool.statusForUser("bob").state()).isEqualTo("DISCONNECTED");

        // When alice pauses her agent, her WS session is cleaned up
        when(agentRepo.findByStatusOrderByUpdatedAtAsc(TradingAgentStatus.RUNNING)).thenReturn(List.of(bobPaper));
        pool.maintainPool();
        assertThat(pool.poolSnapshot()).isEmpty();
        assertThat(pool.statusForUser("alice").state()).isEqualTo("DISCONNECTED");
    }

    private static TradingAgentEntity agent(String user, TradingAgentRunMode mode) {
        TradingAgentEntity e = new TradingAgentEntity();
        e.setUserName(user);
        e.setStatus(TradingAgentStatus.RUNNING);
        e.setRunMode(mode);
        return e;
    }
}
