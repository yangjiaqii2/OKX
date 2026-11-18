package com.example.quant.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
public class TradingDayResolver {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    public LocalDate tradingDate(Instant timestamp) {
        return LocalDate.ofInstant(timestamp == null ? Instant.now() : timestamp, BUSINESS_ZONE);
    }
}
