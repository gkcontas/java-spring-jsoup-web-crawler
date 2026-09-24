package com.gkcontas.crawler.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for the crawler integration tests.
 *
 * <p>The fake site is served by WireMock rather than by a real one on the internet. That
 * keeps the suite deterministic and offline — and it is also the ethically correct
 * choice: a test suite has no business hammering somebody else's server every time it
 * runs, which is exactly the behaviour this project spends so much effort preventing.
 *
 * <p>One server per JVM, started in a static initializer, for the same reason the other
 * projects use a singleton container: the Spring context is cached across test classes,
 * so a per-class server would be torn down while a cached context still pointed at it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class WireMockTestBase {

    protected static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @BeforeEach
    void resetStubs() {
        WIRE_MOCK.resetAll();
    }

    protected static String siteUrl(String path) {
        return "http://127.0.0.1:" + WIRE_MOCK.port() + path;
    }

    protected static String page(String title, String body) {
        return """
                <!DOCTYPE html>
                <html><head><title>%s</title></head>
                <body>%s</body></html>
                """.formatted(title, body);
    }
}
