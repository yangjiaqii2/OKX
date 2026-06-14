package com.example.quant.controller;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quant/system")
public class SystemControlController {
    private final AtomicBoolean emergencyStop = new AtomicBoolean(false);

    @PostMapping("/emergency-stop")
    public Object emergencyStop() {
        emergencyStop.set(true);
        return java.util.Map.of("emergencyStop", true);
    }

    @PostMapping("/resume")
    public Object resume() {
        emergencyStop.set(false);
        return java.util.Map.of("emergencyStop", false);
    }
}
