package com.example.quant.stock;

import com.example.quant.market.DirectionBias;
import com.example.quant.score.ScoreDetail;
import com.example.quant.score.StockScoreCalculator;
import com.example.quant.score.StockScoreInput;
import com.example.quant.stock.dto.StockAnalysisReport;
import com.example.quant.stock.dto.StockCandidate;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StockAnalysisService {
    private final StockCandidateScanner scanner;
    private final StockScoreCalculator scoreCalculator;

    public StockAnalysisService(StockCandidateScanner scanner, StockScoreCalculator scoreCalculator) {
        this.scanner = scanner;
        this.scoreCalculator = scoreCalculator;
    }

    public List<StockCandidate> candidates() {
        return scanner.scan();
    }

    public StockAnalysisReport analyze(String symbol) {
        StockCandidate candidate = scanner.scan().stream()
                .filter(item -> item.symbol().equals(symbol))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AkShare did not return stock: " + symbol));
        ScoreDetail score = scoreCalculator.calculate(new StockScoreInput(
                false,
                false,
                true,
                true,
                candidate.changePercent(),
                candidate.volumeRatio(),
                false,
                8,
                5,
                0
        ));
        return new StockAnalysisReport(
                candidate.symbol(),
                candidate.name(),
                candidate.price(),
                score.score(),
                score.recommendLevel(),
                DirectionBias.WATCH,
                "AkShare实时行情筛选，价格、涨跌幅、成交额和量比来自实时接口。",
                candidate.sector() == null || candidate.sector().isBlank() ? "AkShare当前接口未返回行业板块。" : candidate.sector(),
                "资金流需要后续接入独立真实数据源。",
                "新闻接口未配置真实来源时返回空列表。",
                "公告接口未配置真实来源时返回空列表。",
                score.riskList(),
                score.reasonList(),
                "继续观察成交额、量比和真实公告变化。",
                "跌破关键均线或出现重大负面公告时失效。",
                score.recommendLevel(),
                Instant.now()
        );
    }
}
