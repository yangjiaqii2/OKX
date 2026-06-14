package com.example.quant.news;

import com.example.quant.market.MarketType;
import com.example.quant.news.dto.NewsItem;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CryptoNewsService {
    private final NewsSearchService newsSearchService;

    public CryptoNewsService(NewsSearchService newsSearchService) {
        this.newsSearchService = newsSearchService;
    }

    public List<NewsItem> news(String instId) {
        return newsSearchService.search(MarketType.OKX_SWAP, instId, instId, List.of(instId), 10, 24);
    }
}
