package com.example.quant.task;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class AccountSnapshotTask {
    public ScanTaskLog runOnce() {
        return new ScanTaskLog("account-snapshot", "SUCCESS", "MVP账户快照完成", Instant.now());
    }
}
