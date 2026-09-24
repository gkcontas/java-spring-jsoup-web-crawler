package com.gkcontas.crawler.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class RobotsComplianceIntegrationTest extends WireMockTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldNotFetchAPathTheSiteDisallows() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/robots.txt")).willReturn(plainText("""
                User-agent: *
                Disallow: /private
                """)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", """
                <a href="/public">public</a><a href="/private/secret">private</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/public")).willReturn(html(page("Public", "<h1>ok</h1>"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/private/secret")).willReturn(html(page("Secret", "<h1>no</h1>"))));

        JsonNode job = crawl(siteUrl("/"), 1);

        assertThat(job.get("pagesFetched").asInt()).isEqualTo(2);
        // The disallowed page was never requested at all — not fetched and discarded.
        WIRE_MOCK.verify(0, getRequestedFor(urlEqualTo("/private/secret")));
        WIRE_MOCK.verify(1, getRequestedFor(urlEqualTo("/public")));
    }

    @Test
    void shouldRefuseASingleScrapeOfADisallowedPath() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/robots.txt")).willReturn(plainText("""
                User-agent: *
                Disallow: /admin
                """)));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"x": {"css": "h1"}}}
                                """.formatted(siteUrl("/admin/panel"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("disallowed by robots.txt")));
    }

    @Test
    void shouldCrawlNormallyWhenTheSiteHasNoRobotsFile() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/robots.txt")).willReturn(aResponse().withStatus(404)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", "<h1>hi</h1>"))));

        JsonNode job = crawl(siteUrl("/"), 0);

        // A missing robots.txt declares no restrictions. Reading it as "deny everything"
        // would make the crawler useless on most of the web.
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("pagesFetched").asInt()).isEqualTo(1);
    }

    @Test
    void shouldHonourCrawlDelayWhenItIsLongerThanOurOwn() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/robots.txt")).willReturn(plainText("""
                User-agent: *
                Crawl-delay: 1
                """)));
        WIRE_MOCK.stubFor(get(urlEqualTo("/")).willReturn(html(page("Home", """
                <a href="/one">one</a><a href="/two">two</a>
                """))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/one")).willReturn(html(page("One", "<h1>1</h1>"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/two")).willReturn(html(page("Two", "<h1>2</h1>"))));

        long start = System.currentTimeMillis();
        JsonNode job = crawl(siteUrl("/"), 1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(job.get("pagesFetched").asInt()).isEqualTo(3);
        // The suite configures no politeness delay of its own, so this wait can only come
        // from the site's Crawl-delay: three pages, at least two gaps of one second.
        assertThat(elapsed)
                .as("the site asked for one second between requests")
                .isGreaterThanOrEqualTo(2_000);
    }

    private JsonNode crawl(String seedUrl, int maxDepth) throws Exception {
        String body = mockMvc.perform(post("/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seedUrl": "%s", "maxDepth": %d, "maxPages": 20}
                                """.formatted(seedUrl, maxDepth)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(body).get("jobId").asText();
        await().atMost(Duration.ofSeconds(30))
                .until(() -> !"RUNNING".equals(statusOf(jobId).get("status").asText()));
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

    private static ResponseDefinitionBuilder html(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(body);
    }

    private static ResponseDefinitionBuilder plainText(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody(body);
    }
}
