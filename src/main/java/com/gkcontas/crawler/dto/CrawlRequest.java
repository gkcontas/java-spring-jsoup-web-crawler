package com.gkcontas.crawler.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * @param maxDepth how many link levels to follow; 0 means the seed page only
 * @param maxPages hard ceiling on pages fetched. Both limits are mandatory in practice:
 *                 calendars and paginated archives generate links forever, and a crawler
 *                 without a ceiling never terminates on them.
 */
public record CrawlRequest(
        @NotBlank(message = "must not be blank") String seedUrl,
        @Min(value = 0, message = "must be at least 0") @Max(value = 5, message = "must be at most 5") Integer maxDepth,
        @Min(value = 1, message = "must be at least 1") @Max(value = 500, message = "must be at most 500") Integer maxPages,
        Map<String, @Valid SelectorDefinition> selectors) {
}
