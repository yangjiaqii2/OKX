package com.example.quant.ai;

import org.springframework.stereotype.Component;

@Component
public class StockAiPromptBuilder implements AiPromptBuilder {
    @Override
    public String buildPrompt(Object input) {
        return "请生成A股观察报告，必须包含风险提示、不确定性说明，禁止给出交易执行指令。输入：" + input;
    }
}
