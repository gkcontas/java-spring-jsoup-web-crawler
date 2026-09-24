package com.gkcontas.crawler.dto;

import java.util.Map;

/**
 * @param finalUrl where the request actually ended after redirects; reporting only the
 *                 requested URL would hide that the content came from somewhere else
 */
public record ScrapeResponse(
        String requestedUrl,
        String finalUrl,
        int statusCode,
        String title,
        Map<String, Object> data,
        int linksFound) {
}
