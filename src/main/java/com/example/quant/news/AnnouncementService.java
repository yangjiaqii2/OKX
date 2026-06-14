package com.example.quant.news;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AnnouncementService {
    public List<String> announcements(String symbol) {
        return List.of("MVP公告Adapter预留，未配置真实来源");
    }
}
