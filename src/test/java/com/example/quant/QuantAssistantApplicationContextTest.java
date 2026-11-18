package com.example.quant;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.quant.config.SystemFxRateProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class QuantAssistantApplicationContextTest {
    @Test
    void contextLoads() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(QuantAssistantApplication.class)
                .profiles("test")
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
                )
                .run()) {
            assertThat(context.getBean(SystemFxRateProperties.class).enabled()).isFalse();
        }
    }
}
