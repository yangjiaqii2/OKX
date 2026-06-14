package com.example.quant.ai;

import org.springframework.stereotype.Component;

@Component
public class ContractAiPromptBuilder implements AiPromptBuilder {
    @Override
    public String buildPrompt(Object input) {
        return "请解释OKX合约交易计划，必须说明杠杆、仓位、止损、止盈和风控，不得绕过用户确认。输入：" + input;
    }
}
