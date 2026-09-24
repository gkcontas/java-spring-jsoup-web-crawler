package com.gkcontas.crawler.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.crawler.robots.RobotsRules;
import com.gkcontas.crawler.robots.RobotsTxtService;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RobotsTxtTest {

    @Test
    void shouldApplyTheWildcardGroupWhenNoGroupNamesThisCrawler() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /private
                Disallow: /admin
                """);

        assertThat(rules.isAllowed("/private/page")).isFalse();
        assertThat(rules.isAllowed("/admin")).isFalse();
        assertThat(rules.isAllowed("/public")).isTrue();
    }

    @Test
    void shouldPreferTheGroupThatNamesThisCrawlerOverTheWildcard() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /

                User-agent: gkcontas-crawler
                Disallow: /secret
                """);

        // Only one group applies — they are not merged. Reading both would leave this
        // crawler blocked from everything by the wildcard's "Disallow: /".
        assertThat(rules.isAllowed("/anything")).isTrue();
        assertThat(rules.isAllowed("/secret/x")).isFalse();
    }

    @Test
    void shouldLetTheLongerRuleWinSoAllowCanCarveOutOfDisallow() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /admin
                Allow: /admin/public
                """);

        // Reading the file top to bottom gives the wrong answer here: the narrower rule
        // wins regardless of the order it appears in.
        assertThat(rules.isAllowed("/admin/secret")).isFalse();
        assertThat(rules.isAllowed("/admin/public/page")).isTrue();
    }

    @Test
    void shouldTreatAnEmptyDisallowAsNoRestrictionAtAll() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow:
                """);

        // "Disallow:" with nothing after it means the opposite of "Disallow: /".
        // Recording it as a rule would block the entire site.
        assertThat(rules.isAllowed("/anything")).isTrue();
    }

    @Test
    void shouldSupportTheWildcardAndTheEndAnchor() {
        RobotsRules rules = parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /tmp/*/cache
                """);

        assertThat(rules.isAllowed("/docs/report.pdf")).isFalse();
        assertThat(rules.isAllowed("/docs/report.pdf.html")).isTrue();  // $ anchors the end
        assertThat(rules.isAllowed("/tmp/a/cache")).isFalse();
        assertThat(rules.isAllowed("/tmp/a/data")).isTrue();
    }

    @Test
    void shouldReadCrawlDelay() {
        RobotsRules rules = parse("""
                User-agent: *
                Crawl-delay: 2.5
                """);

        assertThat(rules.crawlDelay()).isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void shouldIgnoreCommentsAndUnknownDirectives() {
        RobotsRules rules = parse("""
                # a comment line
                Sitemap: https://example.com/sitemap.xml
                User-agent: *   # trailing comment
                Disallow: /x
                Host: example.com
                """);

        assertThat(rules.isAllowed("/x")).isFalse();
        assertThat(rules.isAllowed("/y")).isTrue();
    }

    @Test
    void shouldAllowEverythingWhenTheFileIsEmptyOrHasNoUsableGroup() {
        assertThat(parse("").isAllowed("/anything")).isTrue();
        assertThat(parse("Disallow: /orphan-rule-with-no-group").isAllowed("/orphan-rule-with-no-group"))
                .isTrue();
    }

    @Test
    void shouldMatchGroupsOnTheProductTokenOnly() {
        // robots.txt addresses "gkcontas-crawler", not the full User-Agent header with
        // its version and contact URL.
        assertThat(token("gkcontas-crawler/0.1 (+https://github.com/gkcontas)"))
                .isEqualTo("gkcontas-crawler");
        assertThat(token("SimpleBot")).isEqualTo("simplebot");
    }

    private static RobotsRules parse(String content) {
        return invokeStatic("parse", new Class<?>[]{String.class, String.class},
                content, "gkcontas-crawler");
    }

    private static String token(String userAgent) {
        return invokeStatic("tokenOf", new Class<?>[]{String.class}, userAgent);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStatic(String name, Class<?>[] signature, Object... arguments) {
        try {
            Method method = RobotsTxtService.class.getDeclaredMethod(name, signature);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
