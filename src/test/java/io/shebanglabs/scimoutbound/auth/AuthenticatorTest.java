/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.shebanglabs.scimoutbound.http.ScimHttp;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class AuthenticatorTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        httpClient.close();
    }

    @Test
    void bearerHeader() throws Exception {
        assertThat(new BearerAuthenticator("abc").authorization(false)).isEqualTo("Bearer abc");
        assertThat(new BearerAuthenticator("abc").toString()).doesNotContain("abc");
    }

    @Test
    void missingBearerTokenIsNotRetryable() {
        assertThatThrownBy(() -> new BearerAuthenticator(null).authorization(false))
                .isInstanceOf(AuthenticationException.class)
                .satisfies(e -> assertThat(((AuthenticationException) e).isRetryable()).isFalse());
    }

    @Test
    void basicHeader() throws Exception {
        assertThat(new BasicAuthenticator("Aladdin", "open sesame").authorization(false))
                .isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==");
        assertThat(new BasicAuthenticator("Aladdin", "open sesame").toString()).doesNotContain("sesame");
    }

    @Test
    void clientCredentialsFetchesCachesAndRefreshes() throws Exception {
        wm.stubFor(post("/token").willReturn(aResponse().withStatus(200)
                .withBody("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-25T10:00:00Z"));
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 16, clock);
        ClientCredentialsAuthenticator auth = new ClientCredentialsAuthenticator(http, new TokenCache(clock),
                "t1", URI.create(wm.baseUrl() + "/token"), "kc client", "s3cret", "scim.write");

        assertThat(auth.authorization(false)).isEqualTo("Bearer tok-1");
        assertThat(auth.authorization(false)).isEqualTo("Bearer tok-1");
        wm.verify(1, postRequestedFor(urlEqualTo("/token")));
        wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withHeader("Authorization", equalTo("Basic a2MrY2xpZW50OnMzY3JldA=="))
                .withRequestBody(equalTo("grant_type=client_credentials&scope=scim.write")));

        // within 60 s of expiry the token is refreshed proactively
        now.set(now.get().plusSeconds(3600 - 30));
        auth.authorization(false);
        wm.verify(2, postRequestedFor(urlEqualTo("/token")));

        // after a 401 the caller forces a refresh
        auth.authorization(true);
        wm.verify(3, postRequestedFor(urlEqualTo("/token")));
    }

    @Test
    void tokenEndpointErrorsAreClassified() {
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 16,
                Clock.systemUTC());
        wm.stubFor(post("/bad").willReturn(aResponse().withStatus(401)));
        wm.stubFor(post("/down").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> new ClientCredentialsAuthenticator(http, new TokenCache(Clock.systemUTC()), "a",
                URI.create(wm.baseUrl() + "/bad"), "c", "s", null).authorization(false))
                .isInstanceOf(AuthenticationException.class)
                .hasMessage("token endpoint returned HTTP 401")
                .satisfies(e -> assertThat(((AuthenticationException) e).isRetryable()).isFalse());
        assertThatThrownBy(() -> new ClientCredentialsAuthenticator(http, new TokenCache(Clock.systemUTC()), "b",
                URI.create(wm.baseUrl() + "/down"), "c", "s", null).authorization(false))
                .satisfies(e -> assertThat(((AuthenticationException) e).isRetryable()).isTrue());
    }

    @Test
    void tokenWithoutAccessTokenIsRejected() {
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 16,
                Clock.systemUTC());
        wm.stubFor(post("/weird").willReturn(aResponse().withStatus(200).withBody("{\"token_type\":\"Bearer\"}")));

        assertThatThrownBy(() -> new ClientCredentialsAuthenticator(http, new TokenCache(Clock.systemUTC()), "c",
                URI.create(wm.baseUrl() + "/weird"), "c", "s", null).authorization(false))
                .hasMessageContaining("no access_token");
    }
}
