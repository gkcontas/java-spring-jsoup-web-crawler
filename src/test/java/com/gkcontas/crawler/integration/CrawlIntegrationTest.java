package com.gkcontas.crawler.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class CrawlIntegrationTest extends WireMockTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * A small site with the shapes that break naive crawlers: a diamond (two pages
     * linking to the same third one), a cycle back to the root, and an outbound link.
     */
    @BeforeEach
    void stubSite() {
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", """
                <h1>Home</h1>
                <a href="/a">A</a>
                <a href="/b">B</a>
                <a href="http://example.com/elsewhere">external</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/a")).willReturn(html(page("A", """
                <h1>Page A</h1><a href="/c">C</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/b")).willReturn(html(page("B", """
                <h1>Page B</h1><a href="/c">C also from B</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/c")).willReturn(html(page("C", """
                <h1>Page C</h1><a href="/">back home</a>
                """))));
    }

    @Test
    void shouldFetchOnlyTheSeedAtDepthZero() throws Exception {
        JsonNode job = runCrawlToCompletion(siteUrl("/"), 0, 20);

        assertThat(job.get("pagesFetched").asInt()).isEqualTo(1);
        assertThat(urlsOf(job)).containsExactly(siteUrl("/"));
    }

    @Test
    void shouldFollowLinksLevelByLevelAndVisitEachPageOnce() throws Exception {
        JsonNode job = runCrawlToCompletion(siteUrl("/"), 2, 20);

        // Four pages, not five: /c is linked from both /a and /b, and the cycle from /c
        // back to / must not fetch the home page a second time.
        assertThat(urlsOf(job)).containsExactlyInAnyOrder(
                siteUrl("/"), siteUrl("/a"), siteUrl("/b"), siteUrl("/c"));
        WIRE_MOCK.verify(1, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/c")));
    }

    @Test
    void shouldRecordTheDepthEachPageWasFoundAt() throws Exception {
        JsonNode job = runCrawlToCompletion(siteUrl("/"), 2, 20);

        assertThat(depthOf(job, siteUrl("/"))).isZero();
        assertThat(depthOf(job, siteUrl("/a"))).isEqualTo(1);
        assertThat(depthOf(job, siteUrl("/c"))).isEqualTo(2);
    }

    @Test
    void shouldNotLeaveTheSeedHost() throws Exception {
        JsonNode job = runCrawlToCompletion(siteUrl("/"), 2, 20);

        // One outbound link is all it takes for an unrestricted crawler to start walking
        // the entire web.
        assertThat(urlsOf(job)).noneMatch(url -> url.contains("example.com"));
    }

    @Test
    void shouldStopAtThePageBudgetEvenWhenMoreLinksRemain() throws Exception {
        JsonNode job = runCrawlToCompletion(siteUrl("/"), 3, 2);

        assertThat(job.get("pagesFetched").asInt()).isEqualTo(2);
        assertThat(job.get("pagesSkipped").asInt()).isPositive();
    }

    @Test
    void shouldTreatUrlsThatDifferOnlyByTrackingParametersAsOnePage() throws Exception {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", """
                <a href="/a">plain</a>
                <a href="/a?utm_source=newsletter">same page, tagged</a>
                <a href="/a#section">same page, anchored</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/a")).willReturn(html(page("A", "<h1>A</h1>"))));

        JsonNode job = runCrawlToCompletion(siteUrl("/"), 1, 20);

        // Three links, one document. Without normalisation this is three fetches, and on
        // a site that tags its own links it never converges.
        assertThat(job.get("pagesFetched").asInt()).isEqualTo(2);
    }

    @Test
    void shouldKeepGoingWhenOnePageFails() throws Exception {
        WIRE_MOCK.resetAll();
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", """
                <a href="/ok">fine</a><a href="/broken">broken</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/ok")).willReturn(html(page("OK", "<h1>ok</h1>"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/broken")).willReturn(
                aResponse().withStatus(500).withHeader("Content-Type", "application/json").withBody("{}")));

        JsonNode job = runCrawlToCompletion(siteUrl("/"), 1, 20);

        // A real site always has a broken link somewhere. One must not end the crawl.
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(urlsOf(job)).contains(siteUrl("/"), siteUrl("/ok"));
        assertThat(job.get("pagesSkipped").asInt()).isEqualTo(1);
    }

    @Test
    void shouldApplySelectorsToEveryCrawledPage() throws Exception {
        String body = startCrawl("""
                {"seedUrl": "%s", "maxDepth": 1, "maxPages": 20,
                 "selectors": {"heading": {"css": "h1"}}}
                """.formatted(siteUrl("/")));
        JsonNode job = awaitCompletion(objectMapper.readTree(body).get("jobId").asText());

        JsonNode pageA = pageWithUrl(job, siteUrl("/a"));
        assertThat(pageA.get("data").get("heading").asText()).isEqualTo("Page A");
    }

    @Test
    void shouldReturnNotFoundForAnUnknownJobId() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/crawl/{id}", "does-not-exist"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------------

    private String startCrawl(String requestBody) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode runCrawlToCompletion(String seedUrl, int maxDepth, int maxPages) throws Exception {
        String body = startCrawl("""
                {"seedUrl": "%s", "maxDepth": %d, "maxPages": %d}
                """.formatted(seedUrl, maxDepth, maxPages));
        return awaitCompletion(objectMapper.readTree(body).get("jobId").asText());
    }

    private JsonNode awaitCompletion(String jobId) {
        await().atMost(Duration.ofSeconds(30)).until(() -> !"RUNNING".equals(statusOf(jobId).get("status").asText()));
        return statusOf(jobId);
    }

    private JsonNode statusOf(String jobId) {
        try {
            String body = mockMvc.perform(MockMvcRequestBuilders.get("/crawl/{id}", jobId))
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> urlsOf(JsonNode job) {
        List<String> urls = new ArrayList<>();
        job.get("pages").forEach(page -> urls.add(page.get("url").asText()));
        return urls;
    }

    private static int depthOf(JsonNode job, String url) {
        return pageWithUrl(job, url).get("depth").asInt();
    }

    private static JsonNode pageWithUrl(JsonNode job, String url) {
        for (JsonNode page : job.get("pages")) {
            if (url.equals(page.get("url").asText())) {
                return page;
            }
        }
        throw new AssertionError("No crawled page with url " + url);
    }

    private static ResponseDefinitionBuilder html(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(body);
    }
}
