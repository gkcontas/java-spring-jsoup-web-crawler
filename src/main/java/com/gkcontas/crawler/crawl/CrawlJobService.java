package com.gkcontas.crawler.crawl;

import com.gkcontas.crawler.config.CrawlerProperties;
import com.gkcontas.crawler.dto.CrawlJobResponse;
import com.gkcontas.crawler.dto.CrawlRequest;
import com.gkcontas.crawler.dto.CrawledPageResponse;
import com.gkcontas.crawler.exception.BlockedUrlException;
import com.gkcontas.crawler.exception.CrawlJobNotFoundException;
import com.gkcontas.crawler.exception.FetchFailedException;
import com.gkcontas.crawler.fetch.FetchedPage;
import com.gkcontas.crawler.fetch.PageFetcher;
import com.gkcontas.crawler.security.UrlValidator;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs crawls in the background, breadth first, one depth level at a time.
 *
 * <p>Breadth first rather than depth first because the depth limit is the safety rail:
 * with BFS the crawler has fetched everything at distance 1 before it ever looks at
 * distance 2, so stopping at the limit yields a complete picture of the shallow levels
 * instead of one deep thread through the site.
 */
@Service
public class CrawlJobService {

    private static final Logger log = LoggerFactory.getLogger(CrawlJobService.class);

    private final Map<String, CrawlJob> jobsById = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final CrawlerProperties properties;
    private final PageFetcher pageFetcher;
    private final SelectorExtractor extractor;
    private final UrlValidator urlValidator;

    public CrawlJobService(CrawlerProperties properties,
                           PageFetcher pageFetcher,
                           SelectorExtractor extractor,
                           UrlValidator urlValidator) {
        this.properties = properties;
        this.pageFetcher = pageFetcher;
        this.extractor = extractor;
        this.urlValidator = urlValidator;
    }

    public CrawlJob submit(CrawlRequest request) {
        // Validated here, synchronously, so a bad seed answers 400 on the spot instead of
        // becoming a job that fails a second later where nobody is looking.
        URI seed = urlValidator.validate(request.seedUrl());

        int maxDepth = request.maxDepth() == null ? properties.maxDepth() : request.maxDepth();
        int maxPages = request.maxPages() == null ? properties.maxPages() : request.maxPages();

        CrawlJob job = new CrawlJob(seed, maxDepth, maxPages, request.selectors());
        jobsById.put(job.id(), job);
        jobExecutor.submit(() -> execute(job));
        return job;
    }

    public CrawlJob find(String jobId) {
        CrawlJob job = jobsById.get(jobId);
        if (job == null) {
            throw new CrawlJobNotFoundException(jobId);
        }
        return job;
    }

    public CrawlJob cancel(String jobId) {
        CrawlJob job = find(jobId);
        job.requestCancel();
        return job;
    }

    private void execute(CrawlJob job) {
        // ExecutorService is AutoCloseable since Java 19, and close() waits for the
        // submitted tasks to finish — which is exactly the join this level-by-level
        // traversal needs.
        try (ExecutorService levelExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<URI> frontier = List.of(job.seed());

            for (int depth = 0; depth <= job.maxDepth() && !frontier.isEmpty(); depth++) {
                if (job.isCancelRequested()) {
                    job.cancel();
                    return;
                }
                frontier = visitLevel(job, levelExecutor, frontier, depth);
            }
            job.complete();
        } catch (RuntimeException e) {
            log.warn("Crawl {} failed", job.id(), e);
            job.fail(e.toString());
        }
    }

    private List<URI> visitLevel(CrawlJob job, ExecutorService executor, List<URI> frontier, int depth) {
        List<Future<List<URI>>> futures = frontier.stream()
                .map(url -> executor.submit(() -> visit(job, url, depth)))
                .toList();

        // LinkedHashSet keeps the discovery order while removing the duplicates that
        // appear when several pages at this level link to the same next page.
        Set<URI> nextFrontier = new LinkedHashSet<>();
        for (Future<List<URI>> future : futures) {
            try {
                nextFrontier.addAll(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } catch (ExecutionException e) {
                log.debug("A page in crawl {} could not be processed", job.id(), e.getCause());
            }
        }
        return List.copyOf(nextFrontier);
    }

    private List<URI> visit(CrawlJob job, URI url, int depth) {
        if (job.isCancelRequested()) {
            return List.of();
        }
        if (!job.reservePageSlot()) {
            job.countSkipped();
            return List.of();
        }
        try {
            FetchedPage page = pageFetcher.fetch(url.toString());
            Map<String, Object> data = extractor.extract(page.document(), job.selectors());

            job.addPage(new CrawledPageResponse(
                    page.finalUrl().toString(),
                    depth,
                    page.statusCode(),
                    page.document().title(),
                    data));

            return discoverLinks(job, page);
        } catch (BlockedUrlException | FetchFailedException e) {
            // One unreachable or disallowed page is normal on any real site and must not
            // take the whole crawl down with it.
            log.debug("Skipping {} in crawl {}: {}", url, job.id(), e.getMessage());
            job.countSkipped();
            return List.of();
        }
    }

    private List<URI> discoverLinks(CrawlJob job, FetchedPage page) {
        List<URI> discovered = new ArrayList<>();
        for (Element anchor : page.document().select("a[href]")) {
            UrlNormalizer.resolve(page.finalUrl(), anchor.attr("href"))
                    // Staying on the seed's host is what keeps a crawl from wandering off
                    // into the entire web through one outbound link.
                    .filter(candidate -> UrlNormalizer.sameHost(job.seed(), candidate))
                    .ifPresent(candidate -> {
                        if (job.markVisited(UrlNormalizer.normalize(candidate))) {
                            discovered.add(candidate);
                        }
                    });
        }
        return discovered;
    }

    public CrawlJobResponse toResponse(CrawlJob job) {
        return new CrawlJobResponse(
                job.id(),
                job.status().name(),
                job.seed().toString(),
                job.maxDepth(),
                job.maxPages(),
                job.pagesFetched(),
                job.pagesSkipped(),
                job.startedAt(),
                job.finishedAt(),
                job.errorMessage(),
                job.pages());
    }

    @PreDestroy
    void shutdown() {
        jobExecutor.shutdownNow();
    }
}
