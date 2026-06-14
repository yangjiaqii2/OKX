package com.example.quant.news;

import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class StockNewsService {
    private final NewsSearchService newsSearchService;

    public StockNewsService(NewsSearchService newsSearchService) {
        this.newsSearchService = newsSearchService;
    }

    public List<NewsItem> news(String symbol) {
        return newsSearchService.search(MarketType.A_SHARE, symbol, symbol, List.of(symbol), 10, 72);
    }
}
