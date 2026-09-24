package com.gkcontas.crawler.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.crawler")
public record CrawlerProperties(
        String userAgent,
        Duration timeout,
        int maxBodyBytes,
        int maxRedirects,
        int maxRetries,
        Duration retryBackoff,
        Duration politenessDelay,
        int maxDepth,
        int maxPages,
        int robotsCacheMinutes,
        boolean respectRobotsTxt,
        Security security) {

    /**
     * @param allowPrivateAddresses when true the SSRF guard is off entirely and the
     *                              service can reach loopback, link-local and RFC 1918
     *                              addresses. Blunt instrument; prefer the allowlist.
     * @param allowedPrivateHosts   host names or literal addresses exempt from the
     *                              private-address check while the guard stays on for
     *                              everything else. This is how a deployment crawls one
     *                              known internal site without opening the whole network.
     * @param allowedPorts          empty means any port is acceptable.
     */
    public record Security(
            boolean allowPrivateAddresses,
            Set<String> allowedPrivateHosts,
            Set<Integer> allowedPorts) {
    }
}
