/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.shebanglabs.scimoutbound.auth.Authenticator;
import io.shebanglabs.scimoutbound.auth.BearerAuthenticator;
import io.shebanglabs.scimoutbound.scim.ScimPatchOp;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class ScimClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        httpClient.close();
    }

    private ScimClient client(Authenticator auth) {
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20,
                Clock.systemUTC());
        return new ScimClient(http, URI.create(wm.baseUrl() + "/scim/v2/"), auth);
    }

    @Test
    void lookupEncodesAndEscapesFilter() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(200).withBody(
                "{\"totalResults\":1,\"Resources\":[{\"id\":\"r1\",\"userName\":\"a\\\"b\"}]}")));

        var found = client(new BearerAuthenticator("t")).findUserByUserName("a\"b");

        assertThat(found).get().extracting(ScimUser::id).isEqualTo("r1");
        wm.verify(getRequestedFor(urlEqualTo("/scim/v2/Users?filter=userName%20eq%20%22a%5C%22b%22"))
                .withHeader("Authorization", equalTo("Bearer t")));
    }

    @Test
    void lookupReturnsEmptyWhenNothingMatches() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/scim/v2/Users"))
                .willReturn(aResponse().withStatus(200).withBody("{\"totalResults\":0,\"Resources\":[]}")));

        assertThat(client(new BearerAuthenticator("t")).findUserByUserName("nobody")).isEmpty();
    }

    @Test
    void failedLookupIsAnException() {
        wm.stubFor(get(urlPathEqualTo("/scim/v2/Users")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client(new BearerAuthenticator("t")).findUserByUserName("x"))
                .isInstanceOf(ScimException.class)
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isTrue());
    }

    @Test
    void replaceSendsIdInBodyAndPath() throws Exception {
        wm.stubFor(put("/scim/v2/Users/r%201").willReturn(aResponse().withStatus(200)));
        ScimUser user = new ScimUser(List.of(ScimSchemas.USER), null, "kc", "a", null, null, null, null, null,
                null, true, null);

        client(new BearerAuthenticator("t")).replaceUser("r 1", user);

        wm.verify(putRequestedFor(urlEqualTo("/scim/v2/Users/r%201"))
                .withRequestBody(equalToJson("{\"schemas\":[\"" + ScimSchemas.USER + "\"],\"id\":\"r 1\","
                        + "\"externalId\":\"kc\",\"userName\":\"a\",\"active\":true}")));
    }

    @Test
    void patchSendsPatchOp() throws Exception {
        wm.stubFor(patch(urlEqualTo("/scim/v2/Users/r1")).willReturn(aResponse().withStatus(204)));

        ScimResponse response = client(new BearerAuthenticator("t"))
                .patchUser("r1", ScimPatchOp.builder().replace("active", false).build());

        assertThat(response.isSuccess()).isTrue();
        wm.verify(patchRequestedFor(urlEqualTo("/scim/v2/Users/r1")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}")));
    }

    @Test
    void refreshesCredentialsOnceOn401() throws Exception {
        wm.stubFor(get(urlEqualTo("/scim/v2/Users/r1")).withHeader("Authorization", equalTo("Bearer old"))
                .willReturn(aResponse().withStatus(401)));
        wm.stubFor(get(urlEqualTo("/scim/v2/Users/r1")).withHeader("Authorization", equalTo("Bearer new"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        AtomicInteger refreshes = new AtomicInteger();
        Authenticator rotating = new Authenticator() {
            @Override
            public String authorization(boolean refresh) {
                if (refresh) {
                    refreshes.incrementAndGet();
                    return "Bearer new";
                }
                return "Bearer old";
            }

            @Override
            public boolean canRefresh() {
                return true;
            }
        };

        ScimResponse response = client(rotating).getUser("r1");

        assertThat(response.status()).isEqualTo(200);
        assertThat(refreshes).hasValue(1);
    }

    @Test
    void secondUnauthorizedIsReturnedNotLooped() throws Exception {
        wm.stubFor(get(urlEqualTo("/scim/v2/Users/r1")).willReturn(aResponse().withStatus(401)));
        Authenticator alwaysRefreshable = new Authenticator() {
            @Override
            public String authorization(boolean refresh) {
                return "Bearer x";
            }

            @Override
            public boolean canRefresh() {
                return true;
            }
        };

        assertThat(client(alwaysRefreshable).getUser("r1").outcome()).isEqualTo(Outcome.UNAUTHORIZED);
        wm.verify(2, getRequestedFor(urlEqualTo("/scim/v2/Users/r1")));
    }

    @Test
    void serviceProviderConfigFallsBackWhenUnavailable() throws Exception {
        wm.stubFor(get("/scim/v2/ServiceProviderConfig").willReturn(aResponse().withStatus(404)));

        assertThat(client(new BearerAuthenticator("t")).serviceProviderConfig().patchSupported()).isTrue();
    }

    @Test
    void serviceProviderConfigIsRead() throws Exception {
        wm.stubFor(get("/scim/v2/ServiceProviderConfig")
                .willReturn(aResponse().withStatus(200).withBody("{\"patch\":{\"supported\":false}}")));

        assertThat(client(new BearerAuthenticator("t")).serviceProviderConfig().patchSupported()).isFalse();
    }

    @Test
    void listsUsersByIndexAndByCursor() throws Exception {
        wm.stubFor(get(urlPathEqualTo("/scim/v2/Users"))
                .willReturn(aResponse().withStatus(200).withBody("{\"Resources\":[],\"nextCursor\":\"n/2+\"}")));
        ScimClient client = client(new BearerAuthenticator("t"));

        client.listUsers(101, 100, null);
        assertThat(client.listUsersByCursor("", 100, null).nextCursor()).isEqualTo("n/2+");
        client.listUsersByCursor("n/2+", 100, ScimClient.memberOf("g\"1"));

        wm.verify(getRequestedFor(urlEqualTo("/scim/v2/Users?startIndex=101&count=100")));
        wm.verify(getRequestedFor(urlEqualTo("/scim/v2/Users?cursor&count=100")));
        wm.verify(getRequestedFor(urlEqualTo(
                "/scim/v2/Users?cursor=n%2F2%2B&count=100&filter=groups.value%20eq%20%22g%5C%221%22")));
    }
}
