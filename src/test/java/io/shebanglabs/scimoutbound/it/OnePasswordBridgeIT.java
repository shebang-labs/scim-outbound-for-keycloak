/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.ws.rs.core.Response;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * A target that behaves like the 1Password SCIM Bridge 2.9: it advertises PATCH but answers a
 * path-form user PATCH with 400, and accepts PUT. With {@code userUpdateMode: PUT} existing
 * accounts are linked and updated, deleted users are deactivated, and a link whose update failed
 * is never saved.
 */
class OnePasswordBridgeIT {

    private static final String REALM = "onepassword";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static ScimEnvironment env;
    private static String targetId;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment().start();
        env.createRealm(REALM);
        targetId = env.createTarget(REALM, "1password",
                "\"deprovision\":\"DEACTIVATE\",\"syncGroups\":true,\"linkExistingAccounts\":true,"
                        + "\"userUpdateMode\":\"PUT\"");
    }

    @AfterAll
    static void stop() {
        if (env != null) {
            env.close();
        }
    }

    @BeforeEach
    void bridge() {
        ScimStubs.install(env.scim());
        env.scim().register(patch(urlMatching("/scim/v2/Users/.+")).atPriority(1)
                .withRequestBody(matchingJsonPath("$.Operations[0].path"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/scim+json")
                        .withBody("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"400\","
                                + "\"detail\":\"operation did not fit expected field operation structure\"}")));
    }

    /** An account that already exists in 1Password, not provisioned by this Keycloak. */
    private static void existingAccount(String userName) {
        String user = "{\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"],\"id\":\"op-" + userName
                + "\",\"userName\":\"" + userName + "\",\"active\":true,\"meta\":{\"resourceType\":\"User\"}}";
        env.scim().register(get(urlEqualTo("/scim/v2/Users?filter=userName%20eq%20%22" + userName + "%22"))
                .atPriority(1).willReturn(aResponse().withHeader("Content-Type", "application/scim+json")
                        .withBody("{\"totalResults\":1,\"Resources\":[" + user + "]}")));
        env.scim().register(get(urlEqualTo("/scim/v2/Users/op-" + userName)).atPriority(1)
                .willReturn(aResponse().withHeader("Content-Type", "application/scim+json").withBody(user)));
    }

    private static String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setEnabled(true);
        try (Response response = env.admin().realm(REALM).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static long mappedUsers() {
        return env.api("GET", REALM, "targets/" + targetId + "/status", null).json().path("mappedUsers").asLong();
    }

    @Test
    void adminApiStoresTheMode() {
        assertThat(env.api("GET", REALM, "targets/" + targetId, null).json().path("userUpdateMode").asText())
                .isEqualTo("PUT");
        ScimEnvironment.ApiResponse bad = env.api("POST", REALM, "targets",
                "{\"name\":\"bad\",\"baseUrl\":\"" + ScimEnvironment.SCIM_BASE + "\","
                        + "\"auth\":{\"type\":\"BEARER\",\"token\":\"t\"},\"userUpdateMode\":\"MERGE\"}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.json().path("error").asText()).isEqualTo("userUpdateMode must be one of [AUTO, PATCH, PUT]");
    }

    @Test
    void existingAccountIsLinkedUpdatedWithPutAndDeactivatedOnDelete() {
        existingAccount("frank");
        long before = mappedUsers();

        String frankId = createUser("frank");

        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(putRequestedFor(
                urlEqualTo("/scim/v2/Users/op-frank"))
                .withRequestBody(matchingJsonPath("$.externalId", equalTo(frankId)))
                .withRequestBody(matchingJsonPath("$.emails[0].value", equalTo("frank@acme.test")))
                .withRequestBody(matchingJsonPath("$.active", equalTo("true")))));
        await().atMost(WAIT).untilAsserted(() -> assertThat(mappedUsers()).isEqualTo(before + 1));
        assertThat(env.scim().find(patchRequestedFor(urlEqualTo("/scim/v2/Users/op-frank")))).isEmpty();
        assertThat(env.api("GET", REALM, "targets/" + targetId + "/failed", null).json())
                .noneMatch(row -> row.path("resourceId").asText().equals(frankId));

        env.scim().resetRequests();
        env.admin().realm(REALM).users().get(frankId).remove();

        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(putRequestedFor(
                urlEqualTo("/scim/v2/Users/op-frank"))
                .withRequestBody(matchingJsonPath("$.active", equalTo("false")))));
        await().atMost(WAIT).untilAsserted(() -> assertThat(mappedUsers()).isEqualTo(before));
        assertThat(env.scim().find(deleteRequestedFor(anyUrl()))).isEmpty();
        assertThat(env.scim().find(patchRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void failedUpdateAfterLinkLeavesNoMapping() {
        existingAccount("grace");
        env.scim().register(put(urlEqualTo("/scim/v2/Users/op-grace")).atPriority(1)
                .willReturn(aResponse().withStatus(400).withBody("{\"detail\":\"grace is invalid\"}")));
        long before = mappedUsers();

        createUser("grace");

        await().atMost(WAIT).untilAsserted(() -> assertThat(
                env.api("GET", REALM, "targets/" + targetId + "/failed", null).json()).hasSize(1));
        var failed = env.api("GET", REALM, "targets/" + targetId + "/failed", null).json().get(0);
        assertThat(failed.path("lastError").asText()).contains("user update after link failed: HTTP 400");
        assertThat(mappedUsers()).isEqualTo(before);
    }
}
