/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ScimHttpTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final CloseableHttpClient client = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        client.close();
    }

    private ScimHttp http(long maxBytes, Duration readTimeout) {
        return new ScimHttp(client, Duration.ofSeconds(2), readTimeout, maxBytes, Clock.systemUTC());
    }

    private URI uri(String path) {
        return URI.create(wm.baseUrl() + path);
    }

    @Test
    void sendsScimHeadersAndBody() throws Exception {
        wm.stubFor(post("/Users").willReturn(aResponse().withStatus(201).withBody("{\"id\":\"1\"}")));

        ScimResponse response = http(1024, Duration.ofSeconds(2))
                .send("POST", uri("/Users"), "{\"userName\":\"a\"}", Map.of("Authorization", "Bearer t"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).isEqualTo("{\"id\":\"1\"}");
        wm.verify(postRequestedFor(urlEqualTo("/Users"))
                .withHeader("Content-Type", equalTo("application/scim+json; charset=UTF-8"))
                .withHeader("Accept", equalTo("application/scim+json, application/json"))
                .withHeader("Authorization", equalTo("Bearer t"))
                .withHeader("User-Agent", equalTo(ScimHttp.USER_AGENT)));
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        wm.stubFor(get("/Users").willReturn(aResponse().withStatus(302).withHeader("Location", "https://evil.test/")));

        ScimResponse response = http(1024, Duration.ofSeconds(2)).send("GET", uri("/Users"), null, Map.of());

        assertThat(response.status()).isEqualTo(302);
        assertThat(response.outcome()).isEqualTo(Outcome.PERMANENT);
        wm.verify(1, getRequestedFor(urlEqualTo("/Users")));
    }

    @Test
    void rejectsOversizedResponses() {
        wm.stubFor(get("/Users").willReturn(aResponse().withStatus(200).withBody("x".repeat(5000))));

        assertThatThrownBy(() -> http(1000, Duration.ofSeconds(2)).send("GET", uri("/Users"), null, Map.of()))
                .isInstanceOf(ScimTransportException.class)
                .hasMessageContaining("exceeds 1000 bytes")
                .satisfies(e -> assertThat(((ScimTransportException) e).isPermanent()).isTrue());
    }

    @Test
    void rejectsOversizedChunkedResponses() {
        wm.stubFor(get("/Users").willReturn(aResponse().withStatus(200).withBody("y".repeat(5000))
                .withChunkedDribbleDelay(2, 10)));

        assertThatThrownBy(() -> http(1000, Duration.ofSeconds(2)).send("GET", uri("/Users"), null, Map.of()))
                .isInstanceOf(ScimTransportException.class);
    }

    @Test
    void readTimeoutIsRetryable() {
        wm.stubFor(get("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(1500)));

        assertThatThrownBy(() -> http(1024, Duration.ofMillis(300)).send("GET", uri("/slow"), null, Map.of()))
                .isInstanceOf(ScimTransportException.class)
                .hasMessage("read timed out")
                .satisfies(e -> assertThat(((ScimTransportException) e).isPermanent()).isFalse());
    }

    @Test
    void connectionRefusedIsRetryable() {
        ScimHttp http = http(1024, Duration.ofSeconds(1));

        assertThatThrownBy(() -> http.send("GET", URI.create("http://127.0.0.1:1/Users"), null, Map.of()))
                .isInstanceOf(ScimTransportException.class)
                .hasMessageContaining("connection failed");
    }

    @Test
    void exposesRetryAfter() throws Exception {
        wm.stubFor(get("/Users").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "7")));

        ScimResponse response = http(1024, Duration.ofSeconds(2)).send("GET", uri("/Users"), null, Map.of());

        assertThat(response.retryAfter()).contains(Duration.ofSeconds(7));
    }
}
