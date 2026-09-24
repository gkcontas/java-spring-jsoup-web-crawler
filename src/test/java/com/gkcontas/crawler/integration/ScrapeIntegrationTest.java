package com.gkcontas.crawler.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class ScrapeIntegrationTest extends WireMockTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldExtractTextAndAttributesWithTheGivenSelectors() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/product")).willReturn(html(page("Widget",
                """
                <h1 class="product-title">Super Widget</h1>
                <span class="price">R$ 99,90</span>
                <img class="main" src="/img/widget.png"/>
                <a href="/other">other</a>
                """))));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "%s",
                                  "selectors": {
                                    "title": { "css": "h1.product-title" },
                                    "price": { "css": "span.price" },
                                    "image": { "css": "img.main", "attribute": "src" }
                                  }
                                }
                                """.formatted(siteUrl("/product"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(200))
                .andExpect(jsonPath("$.title").value("Widget"))
                .andExpect(jsonPath("$.data.title").value("Super Widget"))
                // Accented text survives, which is what charset detection is for.
                .andExpect(jsonPath("$.data.price").value("R$ 99,90"))
                // The attribute comes back absolute, so the caller does not have to join
                // it with the page URL by hand.
                .andExpect(jsonPath("$.data.image").value(siteUrl("/img/widget.png")))
                .andExpect(jsonPath("$.linksFound").value(1));
    }

    @Test
    void shouldReturnEveryMatchWhenTheSelectorAsksForMultiple() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/list")).willReturn(html(page("List",
                "<ul><li class='item'>one</li><li class='item'>two</li><li class='item'>three</li></ul>"))));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"items": {"css": "li.item", "multiple": true}}}
                                """.formatted(siteUrl("/list"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0]").value("one"));
    }

    @Test
    void shouldReturnNullForASelectorThatMatchesNothing() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/empty")).willReturn(html(page("Empty", "<p>nothing here</p>"))));

        // Null rather than omitting the key: the caller can tell "the selector found
        // nothing" from "I misspelled the field name".
        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"missing": {"css": "h1.nope"}}}
                                """.formatted(siteUrl("/empty"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.missing").doesNotExist());
    }

    @Test
    void shouldReportTheFinalUrlAfterRedirects() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/old")).willReturn(
                aResponse().withStatus(301).withHeader("Location", siteUrl("/new"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/new")).willReturn(html(page("New", "<h1>moved here</h1>"))));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"heading": {"css": "h1"}}}
                                """.formatted(siteUrl("/old"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedUrl").value(siteUrl("/old")))
                .andExpect(jsonPath("$.finalUrl").value(siteUrl("/new")))
                .andExpect(jsonPath("$.data.heading").value("moved here"));
    }

    @Test
    void shouldRejectInvalidCssWithBadRequestRatherThanAServerError() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/page")).willReturn(html(page("Page", "<p>x</p>"))));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"broken": {"css": "h1[unclosed"}}}
                                """.formatted(siteUrl("/page"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid selector"));
    }

    @Test
    void shouldReportAnUpstreamProblemAsBadGatewayNotServerError() throws Exception {
        WIRE_MOCK.stubFor(get(urlEqualTo("/data.json")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{}")));

        // The request was fine; the upstream served something this service cannot parse.
        // Calling that a 500 would send whoever is debugging to the wrong logs.
        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"x": {"css": "h1"}}}
                                """.formatted(siteUrl("/data.json"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not HTML")));
    }

    @Test
    void shouldRejectAnEmptySelectorMap() throws Exception {
        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {}}
                                """.formatted(siteUrl("/page"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("selectors must not be empty"));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder html(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(body);
    }
}
