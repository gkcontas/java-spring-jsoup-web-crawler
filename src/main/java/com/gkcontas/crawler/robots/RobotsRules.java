package com.gkcontas.crawler.robots;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules that apply to this crawler on one host.
 *
 * <p>This implements the parts of RFC 9309 that matter in practice — prefix matching,
 * the {@code *} wildcard, the {@code $} end anchor, and longest-match-wins between Allow
 * and Disallow. It is a deliberate subset, not the full specification.
 */
public record RobotsRules(List<String> disallow, List<String> allow, Duration crawlDelay) {

    public static RobotsRules allowAll() {
        return new RobotsRules(List.of(), List.of(), null);
    }

    /**
     * Longest match wins, and Allow wins a tie. That ordering is what lets a site write
     * "Disallow: /admin" next to "Allow: /admin/public" and have the narrower rule apply
     * — reading the file top to bottom would give the wrong answer.
     */
    public boolean isAllowed(String path) {
        String target = (path == null || path.isEmpty()) ? "/" : path;
        int longestDisallow = longestMatchLength(disallow, target);
        if (longestDisallow < 0) {
            return true;
        }
        return longestMatchLength(allow, target) >= longestDisallow;
    }

    private static int longestMatchLength(List<String> patterns, String path) {
        int longest = -1;
        for (String pattern : patterns) {
            if (matches(pattern, path)) {
                longest = Math.max(longest, pattern.length());
            }
        }
        return longest;
    }

    static boolean matches(String pattern, String path) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        boolean anchoredAtEnd = pattern.endsWith("$");
        String body = anchoredAtEnd ? pattern.substring(0, pattern.length() - 1) : pattern;

        StringBuilder regex = new StringBuilder("^");
        String[] literalParts = body.split("\\*", -1);
        for (int i = 0; i < literalParts.length; i++) {
            if (i > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literalParts[i]));
        }
        if (anchoredAtEnd) {
            regex.append('$');
        }
        return Pattern.compile(regex.toString()).matcher(path).find();
    }
}
