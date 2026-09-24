package com.gkcontas.crawler.dto;

import java.time.Instant;
import java.util.List;

public record CrawlJobResponse(
        String jobId,
        String status,
        String seedUrl,
        int maxDepth,
        int maxPages,
        int pagesFetched,
        int pagesSkipped,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        List<CrawledPageResponse> pages) {
}
