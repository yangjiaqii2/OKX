package com.example.quant.okxtrade;

public interface OkxInstrumentRulesProvider {
    OkxInstrumentRules rules(String instId);
}
