package com.example.quant.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.quant.QuantAssistantApplication;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.mock.env.MockEnvironment;

class EvaluationTradingGuardTest {
    @ParameterizedTest
    @CsvSource({"LIVE,false", "DISABLED,true"})
    void registeredGuardRejectsUnsafeStartupBeforeCreatingApplicationContext(String mode, boolean autoTrade) {
        SpringApplication application = new SpringApplication(QuantAssistantApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        assertThatThrownBy(() -> application.run("--spring.profiles.active=evaluation",
                "--trading.mode=" + mode, "--quant.agent.auto-trade.enabled=" + autoTrade))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evaluation profile cannot start");
    }

    @ParameterizedTest
    @CsvSource({"evaluation,DISABLED,false", "local,LIVE,true"})
    void permitsSafeEvaluationAndLeavesOtherProfilesUnaffected(String profile, String mode, boolean autoTrade) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("trading.mode", mode)
                .withProperty("quant.agent.auto-trade.enabled", Boolean.toString(autoTrade));
        environment.setActiveProfiles(profile);
        assertThatCode(() -> new EvaluationTradingGuard().postProcessEnvironment(environment, null))
                .doesNotThrowAnyException();
    }
}
