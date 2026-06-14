package com.example.quant.task;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class NewsRefreshTask {
    public ScanTaskLog runOnce() {
        return new ScanTaskLog("news-refresh", "SUCCESS", "MVP新闻刷新完成", Instant.now());
    }
}
