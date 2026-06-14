package com.example.quant.news;

import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NewsSearchService {
    private final NewsSourceAdapter adapter;

    public NewsSearchService(NewsSourceAdapter adapter) {
        this.adapter = adapter;
    }

    public List<NewsItem> search(MarketType marketType, String symbol, String name, List<String> keywords, int maxResults, int lookbackHours) {
        return adapter.search(marketType, symbol, name, keywords, maxResults, lookbackHours);
    }
}
