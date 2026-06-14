package com.example.quant.crypto;

import org.springframework.stereotype.Service;

@Service
public class OkxWebSocketService {
    public String status() {
        return "okx-websocket-adapter-ready";
    }
}
