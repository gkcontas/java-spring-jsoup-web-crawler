package com.gkcontas.crawler.dto;

import java.util.Map;

public record CrawledPageResponse(
        String url,
        int depth,
        int statusCode,
        String title,
        Map<String, Object> data) {
}
