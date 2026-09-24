package com.gkcontas.crawler.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gkcontas.crawler.config.CrawlerProperties;
import com.gkcontas.crawler.exception.BlockedUrlException;
import com.gkcontas.crawler.security.UrlValidator;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF table.
 *
 * <p>Literal addresses throughout, so nothing here depends on DNS or on a network being
 * reachable — a security test that silently passes because a lookup failed is worse than
 * no test at all.
 */
class UrlValidatorTest {

    private final UrlValidator validator = validatorWith(false, Set.of(), Set.of());

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/admin",           // loopback: localhost-only admin pages
            "http://127.1.2.3/",                // the whole 127/8 range is loopback
            "http://169.254.169.254/latest/",   // cloud instance metadata, the classic target
            "http://10.0.0.5/",                 // RFC 1918
            "http://192.168.1.1/",              // RFC 1918, home routers
            "http://172.16.0.1/",               // RFC 1918
            "http://100.64.0.1/",               // carrier-grade NAT
            "http://0.0.0.0/",                  // "this host"
            "http://[::1]/",                    // IPv6 loopback
            "http://[fc00::1]/",                // IPv6 unique local
    })
    void shouldRefuseAddressesThatAreNotOnThePublicInternet(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(BlockedUrlException.class)
                .hasMessageContaining("non-public address");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://example.com/file",
            "gopher://example.com/",
            "jar:file:///tmp/x.jar!/",
    })
    void shouldRefuseAnySchemeOtherThanHttpAndHttps(String url) {
        // A fetcher that accepts file: is a local file reader wearing a URL costume.
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(BlockedUrlException.class)
                .hasMessageContaining("only http and https");
    }

    @Test
    void shouldAcceptAPublicAddress() {
        assertThatCode(() -> validator.validate("http://8.8.8.8/")).doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseAUrlWithoutAHost() {
        assertThatThrownBy(() -> validator.validate("http:///just-a-path"))
                .isInstanceOf(BlockedUrlException.class)
                .hasMessageContaining("no host");
    }

    @Test
    void shouldRefuseAPortOutsideTheAllowlist() {
        UrlValidator webPortsOnly = validatorWith(false, Set.of(), Set.of(80, 443));

        // Without a port allowlist a URL becomes a port scanner: :6379 speaks to Redis,
        // :5432 to Postgres, :9200 to Elasticsearch.
        assertThatThrownBy(() -> webPortsOnly.validate("http://8.8.8.8:6379/"))
                .isInstanceOf(BlockedUrlException.class)
                .hasMessageContaining("port 6379 is not allowed");
    }

    @Test
    void shouldTreatAMissingPortAsTheSchemeDefault() {
        UrlValidator webPortsOnly = validatorWith(false, Set.of(), Set.of(80, 443));

        assertThatCode(() -> webPortsOnly.validate("http://8.8.8.8/")).doesNotThrowAnyException();
        assertThatCode(() -> webPortsOnly.validate("https://8.8.8.8/")).doesNotThrowAnyException();
    }

    @Test
    void shouldExemptOnlyTheHostsNamedInTheAllowlist() {
        UrlValidator withExemption = validatorWith(false, Set.of("127.0.0.1"), Set.of());

        assertThatCode(() -> withExemption.validate("http://127.0.0.1:8080/")).doesNotThrowAnyException();
        // The exemption is per host, so everything else private stays refused.
        assertThatThrownBy(() -> withExemption.validate("http://10.0.0.5/"))
                .isInstanceOf(BlockedUrlException.class);
    }

    @Test
    void shouldLetEverythingThroughWhenTheGuardIsExplicitlyDisabled() {
        UrlValidator unguarded = validatorWith(true, Set.of(), Set.of());

        assertThatCode(() -> unguarded.validate("http://169.254.169.254/")).doesNotThrowAnyException();
    }

    @Test
    void shouldNameTheOffendingAddressSoTheRefusalIsDiagnosable() {
        assertThatThrownBy(() -> validator.validate("http://169.254.169.254/latest/meta-data/"))
                .hasMessageContaining("169.254.169.254");
        assertThat(true).isTrue();
    }

    private static UrlValidator validatorWith(boolean allowPrivate,
                                              Set<String> allowedHosts,
                                              Set<Integer> allowedPorts) {
        CrawlerProperties properties = new CrawlerProperties(
                "test-agent/1.0", Duration.ofSeconds(5), 1024, 3, 1,
                Duration.ofMillis(10), Duration.ZERO, 2, 10, 1, true,
                new CrawlerProperties.Security(allowPrivate, allowedHosts, allowedPorts));
        return new UrlValidator(properties);
    }
}
