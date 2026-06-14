package com.example.quant.stock;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StockRiskTagService {
    public List<String> tags(boolean st, boolean suspended) {
        if (st) {
            return List.of("ST风险");
        }
        if (suspended) {
            return List.of("停牌风险");
        }
        return List.of("需关注市场波动");
    }
}
