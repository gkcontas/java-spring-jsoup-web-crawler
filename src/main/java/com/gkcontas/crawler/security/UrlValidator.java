package com.gkcontas.crawler.security;

import com.gkcontas.crawler.config.CrawlerProperties;
import com.gkcontas.crawler.exception.BlockedUrlException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The SSRF guard.
 *
 * <p>An endpoint that fetches a URL chosen by the caller turns the server into a proxy
 * into its own network. Without this check, {@code POST /scrape} with
 * {@code http://169.254.169.254/latest/meta-data/} hands over cloud instance credentials,
 * {@code http://localhost:8080/actuator/env} dumps the configuration, and
 * {@code http://10.0.0.5/} reaches whatever is sitting on the internal network. None of
 * that requires a bug anywhere else — it is the feature working as written.
 *
 * <p><b>Known limitation.</b> Validation resolves the host name and then the HTTP client
 * resolves it again when it connects, so a DNS entry that changes between the two
 * lookups (DNS rebinding) slips past. Closing that gap means connecting to the address
 * that was validated and sending the host name in the {@code Host} header, which needs a
 * custom socket factory. Stated here rather than left implied: a guard whose limits are
 * undocumented gets trusted further than it deserves.
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final CrawlerProperties properties;

    public UrlValidator(CrawlerProperties properties) {
        this.properties = properties;
    }

    public URI validate(String rawUrl) {
        URI uri = parse(rawUrl);
        requireAllowedScheme(uri, rawUrl);
        requireHost(uri, rawUrl);
        requireAllowedPort(uri, rawUrl);
        requireReachableAddressesArePublic(uri, rawUrl);
        return uri;
    }

    private static URI parse(String rawUrl) {
        try {
            return new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw new BlockedUrlException(rawUrl, "not a valid URL");
        }
    }

    private static void requireAllowedScheme(URI uri, String rawUrl) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            // file:, gopher:, jar: and friends are the classic ways to turn a fetcher
            // into a local file reader.
            throw new BlockedUrlException(rawUrl, "only http and https are allowed");
        }
    }

    private static void requireHost(URI uri, String rawUrl) {
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BlockedUrlException(rawUrl, "the URL has no host");
        }
    }

    private void requireAllowedPort(URI uri, String rawUrl) {
        Set<Integer> allowedPorts = properties.security().allowedPorts();
        if (allowedPorts == null || allowedPorts.isEmpty()) {
            return;
        }
        int port = effectivePort(uri);
        if (!allowedPorts.contains(port)) {
            // Without this, a URL is a port scanner: http://internal-host:6379/ speaks to
            // Redis, :5432 to Postgres, :9200 to Elasticsearch.
            throw new BlockedUrlException(rawUrl, "port %d is not allowed".formatted(port));
        }
    }

    private void requireReachableAddressesArePublic(URI uri, String rawUrl) {
        if (properties.security().allowPrivateAddresses() || isExplicitlyAllowed(uri)) {
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new BlockedUrlException(rawUrl, "host could not be resolved");
        }

        for (InetAddress address : addresses) {
            // Every address the name resolves to has to be public. Checking only the
            // first would let a name that returns both a public and a private address
            // through, and which one the client picks is not ours to decide.
            if (isPrivate(address)) {
                throw new BlockedUrlException(rawUrl,
                        "resolves to a non-public address (%s)".formatted(address.getHostAddress()));
            }
        }
    }

    /**
     * An exemption is granted per host name, never per address range: naming
     * {@code wiki.internal} opens exactly that host, while a range would quietly cover
     * everything else living in it.
     */
    private boolean isExplicitlyAllowed(URI uri) {
        Set<String> allowedHosts = properties.security().allowedPrivateHosts();
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return allowedHosts.stream().anyMatch(allowed -> allowed.toLowerCase(Locale.ROOT).equals(host));
    }

    static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress()      // 0.0.0.0, ::
                || address.isLoopbackAddress()   // 127.0.0.0/8, ::1
                || address.isLinkLocalAddress()  // 169.254.0.0/16 — cloud metadata lives here
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7, IPv6 unique local. Inet6Address.isSiteLocalAddress only covers
            // the deprecated fec0::/10, so this range has to be checked by hand.
            return (bytes[0] & 0xFE) == 0xFC;
        }
        // 100.64.0.0/10, carrier-grade NAT. Not "private" by RFC 1918, but it is not the
        // public internet either and a crawler has no business there.
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}
