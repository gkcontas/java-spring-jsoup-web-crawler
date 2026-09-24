package com.gkcontas.crawler.web;

import com.gkcontas.crawler.crawl.ScrapeService;
import com.gkcontas.crawler.dto.ScrapeRequest;
import com.gkcontas.crawler.dto.ScrapeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ScrapeController {

    private final ScrapeService scrapeService;

    public ScrapeController(ScrapeService scrapeService) {
        this.scrapeService = scrapeService;
    }

    /**
     * Synchronous by design: one page, one request, the data in the response. The
     * asynchronous job model only earns its complexity once a crawl spans many pages.
     */
    @PostMapping("/scrape")
    public ScrapeResponse scrape(@Valid @RequestBody ScrapeRequest request) {
        return scrapeService.scrape(request);
    }
}
