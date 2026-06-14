package com.example.quant.stock;

import com.example.quant.stock.dto.StockCandidate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StockCandidateScanner {
    private final AkShareStockClient akShareStockClient;

    public StockCandidateScanner(AkShareStockClient akShareStockClient) {
        this.akShareStockClient = akShareStockClient;
    }

    public List<StockCandidate> scan() {
        return akShareStockClient.candidates();
    }
}
