package com.example.quant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({
            "/login",
            "/contractCandidates",
            "/pendingOrders",
            "/autoTradeRecords",
            "/account-binding",
            "/account-risk",
            "/system-control"
    })
    public String frontend() {
        return "forward:/index.html";
    }
}
