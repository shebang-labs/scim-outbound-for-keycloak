/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.ws.rs.core.Response;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * End to end: admin changes in a real Keycloak reach a SCIM target with exactly the expected
 * requests, after commit and asynchronously.
 */
class UserProvisioningIT {

    private static final String REALM = "acme";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static ScimEnvironment env;
    private static String targetId;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment().start();
        env.createRealm(REALM);
        targetId = env.createTarget(REALM, "app-a", null);
    }

    @AfterAll
    static void stop() {
        if (env != null) {
            env.close();
        }
    }

    @BeforeEach
    void stubs() {
        ScimStubs.install(env.scim());
    }

    private static String createUser(String username, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName("Alice");
        user.setLastName("Ng");
        user.setEnabled(true);
        try (Response response = env.admin().realm(REALM).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    void userLifecycleIsProvisioned() {
        // create
        String userId = createUser("alice", "alice@acme.test");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withHeader("Authorization", equalTo("Bearer test-token"))
                .withHeader("Content-Type", equalTo("application/scim+json; charset=UTF-8"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice")))
                .withRequestBody(matchingJsonPath("$.externalId", equalTo(userId)))
                .withRequestBody(matchingJsonPath("$.emails[0].value", equalTo("alice@acme.test")))
                .withRequestBody(matchingJsonPath("$.active", equalTo("true")))));

        // update
        UserResource alice = env.admin().realm(REALM).users().get(userId);
        UserRepresentation rep = alice.toRepresentation();
        rep.setEmail("alice.ng@acme.test");
        alice.update(rep);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Users/u-alice"))
                .withRequestBody(matchingJsonPath("$.Operations[?(@.path == 'emails')].value[0].value",
                        equalTo("alice.ng@acme.test")))));

        // disable
        rep = alice.toRepresentation();
        rep.setEnabled(false);
        alice.update(rep);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Users/u-alice"))
                .withRequestBody(matchingJsonPath("$.Operations[?(@.path == 'active')].value", equalTo("false")))));

        // delete: deactivated (the safe default), then unlinked
        env.scim().resetRequests();
        long mappedBefore = mappedUsers();
        alice.remove();
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Users/u-alice"))
                .withRequestBody(equalToJson("{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:PatchOp\"],"
                        + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}"))));
        await().atMost(WAIT).untilAsserted(() -> assertThat(mappedUsers()).isEqualTo(mappedBefore - 1));
    }

    private static long mappedUsers() {
        return env.api("GET", REALM, "targets/" + targetId + "/status", null).json().path("mappedUsers").asLong();
    }

    @Test
    void existingRemoteUserIsOnlyLinkedWhenItBelongsToTheKeycloakUser() {
        stubRemoteBob("someone-else");

        String bobId = createUser("bob", "bob@acme.test");

        // someone else's account with the same userName: neither updated nor duplicated
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                env.api("GET", REALM, "targets/" + targetId + "/failed", null).json()).hasSize(1));
        var failed = env.api("GET", REALM, "targets/" + targetId + "/failed", null).json().get(0);
        assertThat(failed.path("resourceId").asText()).isEqualTo(bobId);
        assertThat(failed.path("lastError").asText()).contains("left untouched");
        assertThat(env.scim().find(patchRequestedFor(urlEqualTo("/scim/v2/Users/remote-bob")))).isEmpty();
        assertThat(env.scim().find(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("bob"))))).isEmpty();

        // the account turns out to be the one provisioned for bob: a retry links it
        stubRemoteBob(bobId);
        assertThat(env.api("POST", REALM, "targets/" + targetId + "/failed/" + failed.path("id").asText()
                + "/retry", null).status()).isEqualTo(204);
        env.api("POST", REALM, "worker/run", null);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(
                patchRequestedFor(urlEqualTo("/scim/v2/Users/remote-bob"))));
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                env.api("GET", REALM, "targets/" + targetId + "/failed", null).json()).isEmpty());
    }

    private static void stubRemoteBob(String externalId) {
        env.scim().register(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo(
                "/scim/v2/Users?filter=userName%20eq%20%22bob%22")).atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                        .withBody("{\"totalResults\":1,\"Resources\":[{\"id\":\"remote-bob\",\"externalId\":\""
                                + externalId + "\",\"userName\":\"bob\"}]}")));
    }

    @Test
    void secretsAreMaskedInTheAdminApi() {
        ScimEnvironment.ApiResponse response = env.api("GET", REALM, "targets/" + targetId, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("test-token");
        assertThat(response.json().path("auth").path("token").asText()).isEqualTo("**********");
    }

    @Test
    void permanentFailureIsRecordedAndCanBeRetried() {
        env.scim().register(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/scim/v2/Users"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.userName", equalTo("carol")))
                .willReturn(aResponse().withStatus(400).withBody("{\"detail\":\"carol is invalid\"}")));

        createUser("carol", "carol@acme.test");

        await().atMost(WAIT).untilAsserted(() -> assertThat(
                env.api("GET", REALM, "targets/" + targetId + "/failed", null).json()).hasSize(1));
        var failed = env.api("GET", REALM, "targets/" + targetId + "/failed", null).json().get(0);
        assertThat(failed.path("lastError").asText()).contains("HTTP 400").contains("carol is invalid");

        // the admin fixes the cause on the target side, then retries
        ScimStubs.install(env.scim());
        assertThat(env.api("POST", REALM, "targets/" + targetId + "/failed/" + failed.path("id").asText()
                + "/retry", null).status()).isEqualTo(204);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("carol")))));
        await().atMost(WAIT).untilAsserted(() -> assertThat(
                env.api("GET", REALM, "targets/" + targetId + "/failed", null).json()).isEmpty());
    }

    @Test
    void unavailableTargetNeverBreaksAdminActions() {
        env.scim().register(com.github.tomakehurst.wiremock.client.WireMock.any(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()).atPriority(1)
                .willReturn(aResponse().withStatus(503).withFixedDelay(2000)));

        long started = System.nanoTime();
        String userId = createUser("dave", "dave@acme.test");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(userId).isNotBlank();
        assertThat(elapsedMillis).as("admin request must not wait for the SCIM target").isLessThan(1500);
        await().atMost(WAIT).untilAsserted(() -> assertThat(env.api("GET", REALM,
                "targets/" + targetId + "/status", null).json().path("outbox").path("RETRY_WAIT").asLong())
                .isPositive());
    }

    @Test
    void validationRejectsUnsafeTargets() {
        ScimEnvironment.ApiResponse response = env.api("POST", REALM, "targets",
                "{\"name\":\"bad\",\"baseUrl\":\"ftp://x\",\"auth\":{\"type\":\"BEARER\"}}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("error").asText()).contains("baseUrl").contains("auth.token");
    }

    @Test
    void testConnectionReportsCapabilities() {
        var result = env.api("POST", REALM, "targets/" + targetId + "/test", null).json();

        assertThat(result.path("ok").asBoolean()).isTrue();
        assertThat(result.path("patchSupported").asBoolean()).isTrue();
    }

    @Test
    void patchStubIsUsedForUpdates() {
        // guard against accidental PUTs: updates must go through PATCH when the target supports it
        env.scim().register(patch(urlEqualTo("/scim/v2/Users/u-erin")).atPriority(1)
                .willReturn(aResponse().withStatus(200)));
        String userId = createUser("erin", "erin@acme.test");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("erin")))));

        UserResource erin = env.admin().realm(REALM).users().get(userId);
        UserRepresentation rep = erin.toRepresentation();
        rep.setFirstName("Erin");
        erin.update(rep);

        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Users/u-erin"))));
        assertThat(env.scim().find(com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor(
                urlEqualTo("/scim/v2/Users/u-erin")))).isEmpty();
    }
}
