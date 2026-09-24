package com.gkcontas.crawler.robots;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.gkcontas.crawler.config.CrawlerProperties;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetches, parses and caches {@code robots.txt} per host.
 *
 * <p>Caching is not only an optimisation: asking for {@code /robots.txt} before every
 * single page would double the load the crawler puts on the site, which is the opposite
 * of what respecting the file is for.
 */
@Service
public class RobotsTxtService {

    private static final Logger log = LoggerFactory.getLogger(RobotsTxtService.class);
    private static final int ROBOTS_MAX_BYTES = 512 * 1024;

    private final CrawlerProperties properties;
    private final Cache<String, RobotsRules> cache;
    private final String userAgentToken;

    public RobotsTxtService(CrawlerProperties properties) {
        this.properties = properties;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(properties.robotsCacheMinutes()))
                .maximumSize(1_000)
                .build();
        this.userAgentToken = tokenOf(properties.userAgent());
    }

    public RobotsRules rulesFor(URI uri) {
        if (!properties.respectRobotsTxt()) {
            return RobotsRules.allowAll();
        }
        String host = "%s://%s%s".formatted(
                uri.getScheme(), uri.getHost(), uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return cache.get(host, this::fetchAndParse);
    }

    private RobotsRules fetchAndParse(String hostRoot) {
        try {
            String body = Jsoup.connect(hostRoot + "/robots.txt")
                    .userAgent(properties.userAgent())
                    .timeout((int) properties.timeout().toMillis())
                    .maxBodySize(ROBOTS_MAX_BYTES)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .execute()
                    .body();
            return parse(body, userAgentToken);
        } catch (Exception e) {
            // Fail open. A host with no robots.txt, or one that is briefly unreachable,
            // is not declaring restrictions — refusing to crawl would be the wrong
            // reading of a missing file.
            log.debug("Could not read robots.txt from {}, assuming no restrictions", hostRoot, e);
            return RobotsRules.allowAll();
        }
    }

    static RobotsRules parse(String content, String userAgentToken) {
        Map<String, List<String>> disallowByAgent = new HashMap<>();
        Map<String, List<String>> allowByAgent = new HashMap<>();
        Map<String, Duration> delayByAgent = new HashMap<>();

        List<String> currentAgents = new ArrayList<>();
        boolean readingAgentHeaders = false;

        for (String rawLine : content.split("\\R")) {
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < 0) {
                continue;
            }
            String directive = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();

            if ("user-agent".equals(directive)) {
                // Consecutive User-agent lines share one group; a User-agent line after a
                // rule starts a new group.
                if (!readingAgentHeaders) {
                    currentAgents.clear();
                    readingAgentHeaders = true;
                }
                currentAgents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }

            readingAgentHeaders = false;
            if (currentAgents.isEmpty()) {
                continue; // a rule with no group above it belongs to nobody
            }

            for (String agent : currentAgents) {
                switch (directive) {
                    case "disallow" -> {
                        // "Disallow:" with an empty value means "nothing is disallowed",
                        // so it must not be recorded as a rule matching everything.
                        if (!value.isEmpty()) {
                            disallowByAgent.computeIfAbsent(agent, key -> new ArrayList<>()).add(value);
                        }
                    }
                    case "allow" -> {
                        if (!value.isEmpty()) {
                            allowByAgent.computeIfAbsent(agent, key -> new ArrayList<>()).add(value);
                        }
                    }
                    case "crawl-delay" -> parseDelay(value)
                            .ifPresent(delay -> delayByAgent.put(agent, delay));
                    default -> {
                        // Sitemap and vendor extensions are not rules for us.
                    }
                }
            }
        }

        String group = pickGroup(userAgentToken, disallowByAgent, allowByAgent, delayByAgent);
        if (group == null) {
            return RobotsRules.allowAll();
        }
        return new RobotsRules(
                disallowByAgent.getOrDefault(group, List.of()),
                allowByAgent.getOrDefault(group, List.of()),
                delayByAgent.get(group));
    }

    /**
     * A group naming this crawler takes precedence over the wildcard group — and only
     * one group applies, never both merged together.
     */
    private static String pickGroup(String userAgentToken,
                                    Map<String, List<String>> disallow,
                                    Map<String, List<String>> allow,
                                    Map<String, Duration> delay) {
        if (disallow.containsKey(userAgentToken) || allow.containsKey(userAgentToken)
                || delay.containsKey(userAgentToken)) {
            return userAgentToken;
        }
        if (disallow.containsKey("*") || allow.containsKey("*") || delay.containsKey("*")) {
            return "*";
        }
        return null;
    }

    private static java.util.Optional<Duration> parseDelay(String value) {
        try {
            double seconds = Double.parseDouble(value);
            return seconds <= 0
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(Duration.ofMillis((long) (seconds * 1000)));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * robots.txt groups are matched on the product token, not the whole User-Agent
     * string: "gkcontas-crawler/0.1 (+https://...)" is addressed as "gkcontas-crawler".
     */
    static String tokenOf(String userAgent) {
        String token = userAgent == null ? "" : userAgent.trim();
        int slash = token.indexOf('/');
        if (slash > 0) {
            token = token.substring(0, slash);
        }
        int space = token.indexOf(' ');
        if (space > 0) {
            token = token.substring(0, space);
        }
        return token.toLowerCase(Locale.ROOT);
    }
}
