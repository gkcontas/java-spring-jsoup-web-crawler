package com.gkcontas.crawler.exception;

public class CrawlJobNotFoundException extends RuntimeException {

    public CrawlJobNotFoundException(String jobId) {
        super("Unknown crawl job: '%s'.".formatted(jobId));
    }
}
