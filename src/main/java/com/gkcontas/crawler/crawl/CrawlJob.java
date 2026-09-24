package com.gkcontas.crawler.crawl;

import com.gkcontas.crawler.dto.CrawledPageResponse;
import com.gkcontas.crawler.dto.SelectorDefinition;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * State of one crawl, written by many virtual threads at once.
 *
 * <p>Every mutable field is either a concurrent collection, an atomic, or volatile. A
 * plain {@code ArrayList} of pages here would lose entries under concurrent writes in a
 * way that only shows up occasionally and never in a small test.
 */
public class CrawlJob {

    public enum Status {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final String id = UUID.randomUUID().toString();
    private final URI seed;
    private final int maxDepth;
    private final int maxPages;
    private final Map<String, SelectorDefinition> selectors;
    private final Instant startedAt = Instant.now();

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final List<CrawledPageResponse> pages = new CopyOnWriteArrayList<>();
    private final AtomicInteger reservedPages = new AtomicInteger();
    private final AtomicInteger skippedPages = new AtomicInteger();

    private volatile Status status = Status.RUNNING;
    private volatile Instant finishedAt;
    private volatile String errorMessage;
    private volatile boolean cancelRequested;

    public CrawlJob(URI seed, int maxDepth, int maxPages, Map<String, SelectorDefinition> selectors) {
        this.seed = seed;
        this.maxDepth = maxDepth;
        this.maxPages = maxPages;
        this.selectors = selectors == null ? Map.of() : Map.copyOf(selectors);
        this.visited.add(UrlNormalizer.normalize(seed));
    }

    /**
     * Claims one slot of the page budget, or refuses when the budget is spent.
     *
     * <p>The check and the increment have to be one operation: reading the counter and
     * then incrementing it lets several threads pass the check at the same time and
     * overshoot the limit the caller asked for.
     */
    public boolean reservePageSlot() {
        while (true) {
            int current = reservedPages.get();
            if (current >= maxPages) {
                return false;
            }
            if (reservedPages.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Returns true the first time a URL is seen, false when it has been queued already. */
    public boolean markVisited(String normalizedUrl) {
        return visited.add(normalizedUrl);
    }

    public void addPage(CrawledPageResponse page) {
        pages.add(page);
    }

    public void countSkipped() {
        skippedPages.incrementAndGet();
    }

    public void requestCancel() {
        this.cancelRequested = true;
    }

    public void complete() {
        finish(cancelRequested ? Status.CANCELLED : Status.COMPLETED, null);
    }

    public void cancel() {
        finish(Status.CANCELLED, null);
    }

    public void fail(String message) {
        finish(Status.FAILED, message);
    }

    private void finish(Status finalStatus, String message) {
        this.status = finalStatus;
        this.errorMessage = message;
        this.finishedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public URI seed() {
        return seed;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxPages() {
        return maxPages;
    }

    public Map<String, SelectorDefinition> selectors() {
        return selectors;
    }

    public Status status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public List<CrawledPageResponse> pages() {
        return List.copyOf(pages);
    }

    public int pagesFetched() {
        return pages.size();
    }

    public int pagesSkipped() {
        return skippedPages.get();
    }
}
