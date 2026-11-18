package com.example.quant.crypto;

public enum OkxPrivateWebSocketState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    SUBSCRIBING,
    READY,
    DEGRADED,
    RECONNECTING,
    FAILED
}
