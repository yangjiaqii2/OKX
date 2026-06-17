package com.example.quant;

import com.example.quant.config.AiProperties;
import com.example.quant.config.AgentProperties;
import com.example.quant.config.AkShareProperties;
import com.example.quant.config.AuthProperties;
import com.example.quant.config.ContractProperties;
import com.example.quant.config.NewsProperties;
import com.example.quant.config.OkxProperties;
import com.example.quant.config.StockProperties;
import com.example.quant.config.TradingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        StockProperties.class,
        ContractProperties.class,
        OkxProperties.class,
        NewsProperties.class,
        AgentProperties.class,
        AiProperties.class,
        AkShareProperties.class,
        TradingProperties.class,
        AuthProperties.class
})
public class QuantAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(QuantAssistantApplication.class, args);
    }
}
