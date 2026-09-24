package com.gkcontas.crawler.fetch;

import java.net.URI;
import org.jsoup.nodes.Document;

/**
 * @param requestedUrl what the caller asked for
 * @param finalUrl     where the redirect chain ended — never assume they are the same
 */
public record FetchedPage(URI requestedUrl, URI finalUrl, int statusCode, String contentType, Document document) {
}
