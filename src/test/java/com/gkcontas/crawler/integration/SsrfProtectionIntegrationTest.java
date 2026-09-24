package com.gkcontas.crawler.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SSRF, tested through the API rather than only at the unit level.
 *
 * <p>The suite runs with the guard <em>on</em>; only the loopback host WireMock listens
 * on is exempted. That is what makes these assertions meaningful: everything private is
 * still refused, including the one case that matters most and is almost always missed.
 */
class SsrfProtectionIntegrationTest extends WireMockTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldRefuseToFollowARedirectThatJumpsToAPrivateAddress() throws Exception {
        // The attack: the caller supplies a URL on a host they control, which is public
        // and passes validation. That host answers 302 to the cloud metadata endpoint.
        // A guard that only checks the URL the caller typed hands over the credentials.
        WIRE_MOCK.stubFor(get(urlEqualTo("/innocent")).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://169.254.169.254/latest/meta-data/iam/")));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"x": {"css": "h1"}}}
                                """.formatted(siteUrl("/innocent"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("URL not allowed"))
                .andExpect(jsonPath("$.detail").value(containsString("169.254.169.254")));
    }

    @Test
    void shouldRefuseAPrivateAddressGivenDirectly() throws Exception {
        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "http://10.0.0.5/admin", "selectors": {"x": {"css": "h1"}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("non-public address")));
    }

    @Test
    void shouldRefuseTheFileScheme() throws Exception {
        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "file:///etc/passwd", "selectors": {"x": {"css": "h1"}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("only http and https")));
    }

    @Test
    void shouldRefuseABadSeedUrlSynchronouslyInsteadOfCreatingADoomedJob() throws Exception {
        // Validated on the request thread, so the caller gets 400 immediately rather than
        // a job id that quietly fails a second later where nobody is watching.
        mockMvc.perform(post("/crawl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seedUrl": "http://169.254.169.254/", "maxDepth": 1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("URL not allowed"));
    }

    @Test
    void shouldStopAfterTooManyRedirects() throws Exception {
        // A redirect loop between two public URLs would otherwise spin forever.
        WIRE_MOCK.stubFor(get(urlEqualTo("/loop-a")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", siteUrl("/loop-b"))));
        WIRE_MOCK.stubFor(get(urlEqualTo("/loop-b")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", siteUrl("/loop-a"))));

        mockMvc.perform(post("/scrape")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url": "%s", "selectors": {"x": {"css": "h1"}}}
                                """.formatted(siteUrl("/loop-a"))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(containsString("Too many redirects")));
    }
}
