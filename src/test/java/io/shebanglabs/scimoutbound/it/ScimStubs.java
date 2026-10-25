/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * A minimal, stateless SCIM server made of WireMock stubs: lookups find nothing, creates return
 * a remote id derived from the request ({@code u-<userName>} / {@code g-<displayName>}), and every
 * update or delete succeeds.
 */
public final class ScimStubs {

    private ScimStubs() {
    }

    public static void install(WireMock scim) {
        scim.resetToDefaultMappings();
        scim.resetRequests();
        scim.register(get("/scim/v2/ServiceProviderConfig").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"patch\":{\"supported\":true},\"filter\":{\"supported\":true,\"maxResults\":100}}")));
        scim.register(get(urlPathEqualTo("/scim/v2/Users")).willReturn(list()));
        scim.register(get(urlPathEqualTo("/scim/v2/Groups")).willReturn(list()));
        scim.register(post("/scim/v2/Users").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"u-{{jsonPath request.body '$.userName'}}\","
                        + "\"userName\":\"{{jsonPath request.body '$.userName'}}\"}")));
        scim.register(post("/scim/v2/Groups").willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"g-{{jsonPath request.body '$.displayName'}}\"}")));
        scim.register(any(urlMatching("/scim/v2/(Users|Groups)/.+")).atPriority(10)
                .willReturn(aResponse().withStatus(204)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder list() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:ListResponse\"],"
                        + "\"totalResults\":0,\"Resources\":[]}");
    }
}
