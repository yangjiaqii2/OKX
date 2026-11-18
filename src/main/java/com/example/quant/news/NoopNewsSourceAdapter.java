package com.example.quant.news;

import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "news.provider", havingValue = "NOOP")
public class NoopNewsSourceAdapter implements NewsSourceAdapter {
    @Override
    public List<NewsItem> search(MarketType marketType, String symbol, String name, List<String> keywords,
                                 int maxResults, int lookbackHours) {
        throw new IllegalStateException("news source is disabled");
    }
}
