/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.shebanglabs.scimoutbound.auth.BearerAuthenticator;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UserListingTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private final CloseableHttpClient httpClient = HttpClients.createDefault();

    @AfterEach
    void close() throws Exception {
        httpClient.close();
    }

    private ScimClient client() {
        ScimHttp http = new ScimHttp(httpClient, Duration.ofSeconds(2), Duration.ofSeconds(2), 1 << 20,
                Clock.systemUTC());
        return new ScimClient(http, URI.create(wm.baseUrl()), new BearerAuthenticator("t"));
    }

    private static void page(String query, String body) {
        wm.stubFor(get(urlEqualTo("/Users?" + query)).willReturn(aResponse().withStatus(200).withBody(body)));
    }

    private static String users(String... ids) {
        return Arrays.stream(ids).map(id -> "{\"id\":\"" + id + "\",\"userName\":\"" + id + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void followsCursorsLikeAwsIdentityCenter() throws Exception {
        // AWS answers cursor requests without totalResults and does not support startIndex
        page("cursor&count=100", "{\"itemsPerPage\":2,\"nextCursor\":\"c2\",\"Resources\":" + users("a", "b") + "}");
        page("cursor=c2&count=100", "{\"itemsPerPage\":1,\"Resources\":" + users("c") + "}");

        assertThat(UserListing.all(client(), null)).containsOnlyKeys("a", "b", "c");
        wm.verify(0, getRequestedFor(urlEqualTo("/Users?startIndex=3&count=100")));
    }

    @Test
    void continuesByIndexWhenTheServerIgnoresTheCursor() throws Exception {
        page("cursor&count=100", "{\"totalResults\":3,\"startIndex\":1,\"Resources\":" + users("a", "b") + "}");
        page("startIndex=3&count=100", "{\"totalResults\":3,\"startIndex\":3,\"Resources\":" + users("c") + "}");

        assertThat(UserListing.all(client(), null)).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void fallsBackToIndexWhenTheServerRejectsTheCursor() throws Exception {
        wm.stubFor(get(urlEqualTo("/Users?cursor&count=100")).willReturn(aResponse().withStatus(400)
                .withBody("{\"status\":\"400\",\"scimType\":\"invalidValue\"}")));
        page("startIndex=1&count=100", "{\"totalResults\":1,\"Resources\":" + users("a") + "}");

        assertThat(UserListing.all(client(), null)).containsOnlyKeys("a");
    }

    @Test
    void singlePageNeedsNoFurtherRequest() throws Exception {
        page("cursor&count=100", "{\"totalResults\":2,\"Resources\":" + users("a", "b") + "}");

        assertThat(UserListing.all(client(), null)).containsOnlyKeys("a", "b");
        wm.verify(1, getRequestedFor(urlEqualTo("/Users?cursor&count=100")));
    }

    @Test
    void appliesTheFilterToEveryPage() throws Exception {
        String filter = "&filter=groups.value%20eq%20%22gr-1%22";
        page("cursor&count=100" + filter, "{\"nextCursor\":\"c2\",\"Resources\":" + users("a") + "}");
        page("cursor=c2&count=100" + filter, "{\"Resources\":" + users("b") + "}");

        assertThat(UserListing.all(client(), ScimClient.memberOf("gr-1"))).containsOnlyKeys("a", "b");
    }

    @Test
    void stopsWhenTheServerRepeatsACursor() {
        page("cursor&count=100", "{\"nextCursor\":\"c2\",\"Resources\":" + users("a") + "}");
        page("cursor=c2&count=100", "{\"nextCursor\":\"c2\",\"Resources\":" + users("b") + "}");

        assertThatThrownBy(() -> UserListing.all(client(), null))
                .isInstanceOf(ScimException.class).hasMessageContaining("repeated a pagination cursor");
    }

    @Test
    void stopsWhenTheServerIgnoresStartIndex() {
        // without the guard this would count only the first page and plan creates for the rest
        page("cursor&count=100", "{\"totalResults\":3,\"Resources\":" + users("a", "b") + "}");
        page("startIndex=3&count=100", "{\"totalResults\":3,\"Resources\":" + users("a", "b") + "}");

        assertThatThrownBy(() -> UserListing.all(client(), null))
                .isInstanceOf(ScimException.class).hasMessageContaining("may not support startIndex");
    }

    @Test
    void otherErrorsAreNotTreatedAsMissingCursorSupport() {
        wm.stubFor(get(urlEqualTo("/Users?cursor&count=100")).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> UserListing.all(client(), null))
                .isInstanceOf(ScimException.class)
                .satisfies(e -> assertThat(((ScimException) e).isRetryable()).isTrue());
    }
}
