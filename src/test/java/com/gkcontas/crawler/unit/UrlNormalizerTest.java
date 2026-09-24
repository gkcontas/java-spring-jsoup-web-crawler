package com.gkcontas.crawler.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gkcontas.crawler.crawl.UrlNormalizer;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UrlNormalizerTest {

    @Test
    void shouldCollapseTheManySpellingsOfOnePage() {
        String canonical = UrlNormalizer.normalize(URI.create("https://Example.COM:443/page/"));

        // Uppercase host, the default port, the trailing slash and the fragment are all
        // noise. Treating them as distinct URLs makes the crawler fetch one document
        // four times and burn its page budget on nothing.
        assertThat(UrlNormalizer.normalize(URI.create("https://example.com/page")))
                .isEqualTo(canonical);
        assertThat(UrlNormalizer.normalize(URI.create("https://example.com/page#section")))
                .isEqualTo(canonical);
        assertThat(UrlNormalizer.normalize(URI.create("HTTPS://EXAMPLE.COM/page/")))
                .isEqualTo(canonical);
    }

    @Test
    void shouldDropTrackingParametersButKeepRealOnes() {
        String normalized = UrlNormalizer.normalize(
                URI.create("https://example.com/search?q=java&utm_source=twitter&fbclid=abc&page=2"));

        // On a site that appends tracking parameters to its own links, keeping them means
        // every visit produces a "new" URL and the crawl never terminates.
        assertThat(normalized).isEqualTo("https://example.com/search?page=2&q=java");
    }

    @Test
    void shouldOrderQueryParametersSoEquivalentUrlsMatch() {
        assertThat(UrlNormalizer.normalize(URI.create("https://example.com/x?b=2&a=1")))
                .isEqualTo(UrlNormalizer.normalize(URI.create("https://example.com/x?a=1&b=2")));
    }

    @Test
    void shouldKeepANonDefaultPort() {
        assertThat(UrlNormalizer.normalize(URI.create("http://example.com:8080/x")))
                .isEqualTo("http://example.com:8080/x");
    }

    @Test
    void shouldTreatAnEmptyPathAsRoot() {
        assertThat(UrlNormalizer.normalize(URI.create("https://example.com")))
                .isEqualTo("https://example.com/");
    }

    @Test
    void shouldResolveRelativeLinksAgainstThePage() {
        URI base = URI.create("https://example.com/section/index.html");

        assertThat(UrlNormalizer.resolve(base, "../other.html"))
                .contains(URI.create("https://example.com/other.html"));
        assertThat(UrlNormalizer.resolve(base, "/absolute"))
                .contains(URI.create("https://example.com/absolute"));
        assertThat(UrlNormalizer.resolve(base, "https://other.com/x"))
                .contains(URI.create("https://other.com/x"));
    }

    @Test
    void shouldRejectLinksThatAreNotPagesToFetch() {
        URI base = URI.create("https://example.com/");

        // These appear in the href of real anchors constantly; following them produces
        // errors, never content.
        assertThat(UrlNormalizer.resolve(base, "#top")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "mailto:someone@example.com")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "tel:+551140028922")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "javascript:void(0)")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "data:text/html,<b>x</b>")).isEmpty();
        assertThat(UrlNormalizer.resolve(base, "   ")).isEmpty();
    }

    @Test
    void shouldCompareHostsIgnoringCase() {
        assertThat(UrlNormalizer.sameHost(
                URI.create("https://Example.com/a"), URI.create("https://example.COM/b"))).isTrue();
        assertThat(UrlNormalizer.sameHost(
                URI.create("https://example.com/a"), URI.create("https://other.com/b"))).isFalse();
    }

    @Test
    void shouldNotThrowOnAMalformedHref() {
        // Real pages contain broken markup; a crawler that throws on one bad href stops
        // dead on the first page that has one.
        Optional<URI> resolved = UrlNormalizer.resolve(URI.create("https://example.com/"), "http://[bad");

        assertThat(resolved).isEmpty();
    }
}
