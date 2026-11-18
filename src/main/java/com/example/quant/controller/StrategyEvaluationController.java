package com.example.quant.controller;

import com.example.quant.agent.evaluation.StrategyEvaluationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent/strategy-evaluation")
public class StrategyEvaluationController {
    private final StrategyEvaluationService service;
    public StrategyEvaluationController(StrategyEvaluationService service) { this.service = service; }
    @GetMapping
    public StrategyEvaluationService.Summary summary(
            @RequestParam(defaultValue="RULE_FIRST_AI_RANK") String selectionPolicy,
            @RequestParam(defaultValue="S1_V2") String exitPolicy) {
        return service.summary(selectionPolicy, exitPolicy);
    }
}
