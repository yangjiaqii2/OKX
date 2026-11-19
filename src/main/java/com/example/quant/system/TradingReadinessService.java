package com.example.quant.system;

import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TradingReadinessService {
    private boolean startupBlocked = true;
    private volatile TradingEngineState state = TradingEngineState.INITIALIZING;
    private volatile String reason = "STARTUP_RECOVERY_PENDING";
    private volatile Instant updatedAt = Instant.now();

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, reason, updatedAt);
    }

    public boolean ready() {
        return state == TradingEngineState.READY;
    }

    public synchronized void updateStartup(TradingEngineState next, String reason) {
        startupBlocked = next == TradingEngineState.BLOCKED || next == TradingEngineState.RECOVERING;
        publish(next, reason);
    }

    public synchronized void updateConnection(TradingEngineState next, String reason) {
        if (!startupBlocked) publish(next, reason);
    }

    public synchronized void update(TradingEngineState next, String nextReason) {
        updateStartup(next, nextReason);
    }

    private void publish(TradingEngineState next, String nextReason) {
        state = next;
        reason = nextReason;
        updatedAt = Instant.now();
    }

    public record Snapshot(TradingEngineState state, String reason, Instant updatedAt) {
    }
}
