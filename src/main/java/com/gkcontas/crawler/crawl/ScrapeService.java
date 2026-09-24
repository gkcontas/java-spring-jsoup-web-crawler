package com.gkcontas.crawler.crawl;

import com.gkcontas.crawler.dto.ScrapeRequest;
import com.gkcontas.crawler.dto.ScrapeResponse;
import com.gkcontas.crawler.fetch.FetchedPage;
import com.gkcontas.crawler.fetch.PageFetcher;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ScrapeService {

    private final PageFetcher pageFetcher;
    private final SelectorExtractor extractor;

    public ScrapeService(PageFetcher pageFetcher, SelectorExtractor extractor) {
        this.pageFetcher = pageFetcher;
        this.extractor = extractor;
    }

    public ScrapeResponse scrape(ScrapeRequest request) {
        FetchedPage page = pageFetcher.fetch(request.url());
        Map<String, Object> data = extractor.extract(page.document(), request.selectors());

        return new ScrapeResponse(
                request.url(),
                page.finalUrl().toString(),
                page.statusCode(),
                page.document().title(),
                data,
                page.document().select("a[href]").size());
    }
}
