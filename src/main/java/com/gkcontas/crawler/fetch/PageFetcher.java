package com.gkcontas.crawler.fetch;

import com.gkcontas.crawler.config.CrawlerProperties;
import com.gkcontas.crawler.crawl.UrlNormalizer;
import com.gkcontas.crawler.exception.BlockedUrlException;
import com.gkcontas.crawler.exception.FetchFailedException;
import com.gkcontas.crawler.robots.RobotsRules;
import com.gkcontas.crawler.robots.RobotsTxtService;
import com.gkcontas.crawler.security.UrlValidator;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PageFetcher {

    private static final Logger log = LoggerFactory.getLogger(PageFetcher.class);
    private static final Set<Integer> REDIRECT_STATUS_CODES = Set.of(301, 302, 303, 307, 308);

    private final CrawlerProperties properties;
    private final UrlValidator urlValidator;
    private final RobotsTxtService robotsTxtService;
    private final HostRateLimiter rateLimiter;

    public PageFetcher(CrawlerProperties properties,
                       UrlValidator urlValidator,
                       RobotsTxtService robotsTxtService,
                       HostRateLimiter rateLimiter) {
        this.properties = properties;
        this.urlValidator = urlValidator;
        this.robotsTxtService = robotsTxtService;
        this.rateLimiter = rateLimiter;
    }

    public FetchedPage fetch(String rawUrl) {
        URI requested = urlValidator.validate(rawUrl);
        return fetchFollowingRedirects(requested);
    }

    private FetchedPage fetchFollowingRedirects(URI requested) {
        URI current = requested;

        for (int hop = 0; hop <= properties.maxRedirects(); hop++) {
            // Revalidate on EVERY hop. Checking only the URL the caller supplied is the
            // single most common way an SSRF guard is defeated: the attacker points at a
            // host they control, which answers 302 Location: http://169.254.169.254/.
            urlValidator.validate(current.toString());
            requireRobotsAllows(current);

            Connection.Response response = executeWithRetries(current);

            if (REDIRECT_STATUS_CODES.contains(response.statusCode())) {
                current = nextHop(current, response);
                continue;
            }
            return toFetchedPage(requested, current, response);
        }
        throw new FetchFailedException(
                "Too many redirects (limit %d) starting at %s".formatted(properties.maxRedirects(), requested));
    }

    private URI nextHop(URI current, Connection.Response response) {
        String location = response.header("Location");
        if (location == null || location.isBlank()) {
            throw new FetchFailedException("Redirect from %s has no Location header".formatted(current));
        }
        return UrlNormalizer.resolve(current, location)
                .orElseThrow(() -> new FetchFailedException(
                        "Redirect from %s points at an unusable target: %s".formatted(current, location)));
    }

    private void requireRobotsAllows(URI uri) {
        RobotsRules rules = robotsTxtService.rulesFor(uri);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (!rules.isAllowed(path)) {
            throw new BlockedUrlException(uri.toString(), "disallowed by robots.txt");
        }
    }

    private Connection.Response executeWithRetries(URI uri) {
        IOException lastFailure = null;

        for (int attempt = 0; attempt <= properties.maxRetries(); attempt++) {
            try {
                applyPoliteness(uri);
                return Jsoup.connect(uri.toString())
                        .userAgent(properties.userAgent())
                        .timeout((int) properties.timeout().toMillis())
                        .maxBodySize(properties.maxBodyBytes())
                        // Redirects are followed by hand above so each hop can be
                        // revalidated; letting Jsoup do it would skip the check.
                        .followRedirects(false)
                        // Without this an HTTP error throws, and a 404 in the middle of a
                        // crawl is information, not a reason to abort.
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute();
            } catch (IOException e) {
                lastFailure = e;
                // Only transport failures are retried. An HTTP 4xx is an answer, and
                // asking again produces the same answer plus load on the host.
                if (attempt < properties.maxRetries()) {
                    backoff(attempt);
                }
            }
        }
        throw new FetchFailedException("Could not fetch %s".formatted(uri), lastFailure);
    }

    private void applyPoliteness(URI uri) {
        // A Crawl-delay in robots.txt is the site telling us its preferred pace. When it
        // asks for more than our default, the site wins.
        Duration configured = properties.politenessDelay();
        Duration fromRobots = Optional.ofNullable(robotsTxtService.rulesFor(uri).crawlDelay())
                .orElse(Duration.ZERO);
        Duration delay = configured.compareTo(fromRobots) >= 0 ? configured : fromRobots;
        try {
            rateLimiter.acquire(uri.getHost(), delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchFailedException("Interrupted while waiting to be polite to " + uri.getHost(), e);
        }
    }

    private void backoff(int attempt) {
        long millis = properties.retryBackoff().toMillis() * (1L << attempt);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchFailedException("Interrupted while backing off", e);
        }
    }

    private FetchedPage toFetchedPage(URI requested, URI finalUrl, Connection.Response response) {
        String contentType = response.contentType();
        if (!isHtml(contentType)) {
            throw new FetchFailedException(
                    "%s returned %s, which is not HTML".formatted(finalUrl, contentType));
        }
        try {
            // Jsoup reads the charset from the Content-Type header and from any meta tag,
            // which is what keeps accented text from arriving as mojibake.
            return new FetchedPage(requested, finalUrl, response.statusCode(), contentType, response.parse());
        } catch (IOException e) {
            throw new FetchFailedException("Could not parse the response from " + finalUrl, e);
        }
    }

    private static boolean isHtml(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/html") || normalized.startsWith("application/xhtml+xml");
    }

    static Logger logger() {
        return log;
    }
}
