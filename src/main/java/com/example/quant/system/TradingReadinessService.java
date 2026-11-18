package com.example.quant.system;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TradingReadinessService {
    private volatile TradingEngineState state = TradingEngineState.INITIALIZING;
    private volatile String reason = "STARTUP_RECOVERY_PENDING";
    private volatile Instant updatedAt = Instant.now();

    public Snapshot snapshot() {
        return new Snapshot(state, reason, updatedAt);
    }

    public boolean ready() {
        return state == TradingEngineState.READY;
    }

    public synchronized void update(TradingEngineState next, String nextReason) {
        state = next;
        reason = nextReason;
        updatedAt = Instant.now();
    }

    public record Snapshot(TradingEngineState state, String reason, Instant updatedAt) {
    }
}
