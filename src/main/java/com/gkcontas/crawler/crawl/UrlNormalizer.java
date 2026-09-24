package com.gkcontas.crawler.crawl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reduces the many spellings of one page to a single key.
 *
 * <p>Without this, {@code /page}, {@code /page/}, {@code /page#section} and
 * {@code /page?utm_source=twitter} are four different URLs pointing at one document. A
 * crawler that treats them as four fetches the same content over and over, wastes the
 * page budget, and on a site that generates tracking links can loop forever.
 */
public final class UrlNormalizer {

    /**
     * Parameters that identify where a visitor came from, never which document is served.
     */
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "fbclid", "gclid", "msclkid", "mc_cid", "mc_eid", "igshid", "_ga");

    private UrlNormalizer() {
    }

    public static String normalize(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);

        int port = uri.getPort();
        boolean defaultPort = port == -1
                || ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);

        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        StringBuilder normalized = new StringBuilder(scheme).append("://").append(host);
        if (!defaultPort) {
            normalized.append(':').append(port);
        }
        normalized.append(path);

        String query = cleanQuery(uri.getRawQuery());
        if (!query.isEmpty()) {
            normalized.append('?').append(query);
        }
        // The fragment is never sent to the server, so it cannot identify a document.
        return normalized.toString();
    }

    /**
     * Resolves a link found in a page against the page's own URL, rejecting anything
     * that is not a usable http(s) target.
     */
    public static Optional<URI> resolve(URI base, String href) {
        if (href == null || href.isBlank()) {
            return Optional.empty();
        }
        String trimmed = href.trim();
        // mailto:, tel:, javascript: and in-page anchors are not pages to crawl.
        if (trimmed.startsWith("#") || trimmed.startsWith("mailto:")
                || trimmed.startsWith("tel:") || trimmed.startsWith("javascript:")
                || trimmed.startsWith("data:")) {
            return Optional.empty();
        }
        try {
            URI resolved = base.resolve(trimmed);
            if (resolved.getHost() == null || resolved.getScheme() == null) {
                return Optional.empty();
            }
            String scheme = resolved.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean sameHost(URI first, URI second) {
        return first.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private static String cleanQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String parameter : rawQuery.split("&")) {
            if (parameter.isBlank()) {
                continue;
            }
            String name = parameter.split("=", 2)[0];
            if (!TRACKING_PARAMETERS.contains(name.toLowerCase(Locale.ROOT))) {
                kept.add(parameter);
            }
        }
        // Sorted so ?a=1&b=2 and ?b=2&a=1 collapse to the same key.
        kept.sort(String::compareTo);
        return String.join("&", kept);
    }

    public static URI toUri(String url) throws URISyntaxException {
        return new URI(url);
    }

    static List<String> trackingParameters() {
        return Arrays.asList(TRACKING_PARAMETERS.toArray(String[]::new));
    }
}
