package com.example.quant.news;

import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import java.util.List;

public interface NewsSourceAdapter {
    List<NewsItem> search(MarketType marketType, String symbol, String name, List<String> keywords, int maxResults, int lookbackHours);
}
