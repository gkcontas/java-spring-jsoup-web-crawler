package com.gkcontas.crawler.web;

import com.gkcontas.crawler.crawl.CrawlJobService;
import com.gkcontas.crawler.dto.CrawlJobResponse;
import com.gkcontas.crawler.dto.CrawlRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/crawl")
public class CrawlController {

    private final CrawlJobService crawlJobService;

    public CrawlController(CrawlJobService crawlJobService) {
        this.crawlJobService = crawlJobService;
    }

    /**
     * Returns 202 with a job id. A crawl takes as long as the site and the politeness
     * delay make it take, which is not something to hold an HTTP connection open for.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CrawlJobResponse start(@Valid @RequestBody CrawlRequest request) {
        return crawlJobService.toResponse(crawlJobService.submit(request));
    }

    @GetMapping("/{jobId}")
    public CrawlJobResponse status(@PathVariable String jobId) {
        return crawlJobService.toResponse(crawlJobService.find(jobId));
    }

    /**
     * Cancellation is cooperative: the flag is set here and the workers stop at their
     * next checkpoint. Interrupting mid-request would leave a half-read connection and
     * teach the target host nothing good about this crawler.
     */
    @DeleteMapping("/{jobId}")
    public CrawlJobResponse cancel(@PathVariable String jobId) {
        return crawlJobService.toResponse(crawlJobService.cancel(jobId));
    }
}
