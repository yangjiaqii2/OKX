package com.example.quant.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.quant.account.AccountSnapshotService;
import com.example.quant.account.dto.AccountSummary;
import com.example.quant.auth.AuthSessionService;
import com.example.quant.config.SystemFxRateProperties;
import com.example.quant.config.TradingProperties;
import com.example.quant.config.WebConfig;
import com.example.quant.crypto.OkxWebSocketService;
import com.example.quant.system.FxRateService;
import com.example.quant.system.SystemControlService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemControlController.class)
@Import({WebConfig.class, SystemControlControllerWebTest.TestBeans.class})
class SystemControlControllerWebTest {
    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountSnapshotService accountSnapshotService;

    @MockBean
    private AuthSessionService authSessionService;

    @Test
    void exposesFxRateUnderApiQuantSystemPath() throws Exception {
        when(authSessionService.currentUsername(TOKEN)).thenReturn(Optional.of("admin"));

        mockMvc.perform(get("/api/quant/system/fx-rate")
                        .header("Authorization", "Bearer " + TOKEN)
                        .param("base", "USD")
                        .param("quote", "CNY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("USD"))
                .andExpect(jsonPath("$.quote").value("CNY"))
                .andExpect(jsonPath("$.rate").value(7.31))
                .andExpect(jsonPath("$.source").value("TEST_CONFIG"));
    }

    @Test
    void exposesHonestRuntimeConnectionStatus() throws Exception {
        when(authSessionService.currentUsername(TOKEN)).thenReturn(Optional.of("admin"));
        when(accountSnapshotService.summary()).thenReturn(
                new AccountSummary(BigDecimal.TEN, BigDecimal.TEN, "OKX_REAL", "ok"));

        mockMvc.perform(get("/api/quant/system/runtime-status")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingMode").value("LIVE"))
                .andExpect(jsonPath("$.okxRest").value("CONNECTED"))
                .andExpect(jsonPath("$.publicWebSocket").value("NOT_IMPLEMENTED"))
                .andExpect(jsonPath("$.privateWebSocket").value("DISCONNECTED"));
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        SystemControlService systemControlService() {
            return new SystemControlService(tradingProperties());
        }

        @Bean
        TradingProperties tradingProperties() {
            return new TradingProperties("SEMI_AUTO", true, true, 120, false);
        }

        @Bean
        OkxWebSocketService okxWebSocketService() {
            return new OkxWebSocketService();
        }

        @Bean
        FxRateService fxRateService() {
            SystemFxRateProperties properties = new SystemFxRateProperties();
            properties.setRate(new BigDecimal("7.31"));
            properties.setSource("TEST_CONFIG");
            return new FxRateService(properties);
        }
    }
}
