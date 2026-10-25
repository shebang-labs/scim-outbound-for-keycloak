/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Reconciliation: provisioning users that existed before the target, dry run, arming, and the
 * removal safety brake.
 */
class ReconciliationIT {

    private static final String REALM = "recon";
    private static final Duration WAIT = Duration.ofSeconds(40);
    private static ScimEnvironment env;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment().start();
        env.createRealm(REALM);
        ScimStubs.install(env.scim());
        ScimStubs.installAt(env.scim(), "/brake/scim/v2");
    }

    @AfterAll
    static void stop() {
        if (env != null) {
            env.close();
        }
    }

    private static String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = env.admin().realm(REALM).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static JsonNode lastReport(String targetId) {
        String summary = env.api("GET", REALM, "targets/" + targetId + "/status", null).json()
                .path("lastReconcileSummary").asText(null);
        try {
            return summary == null ? null : ScimJson.mapper().readTree(summary);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode reconcileAndWait(String targetId, boolean dryRun) {
        JsonNode before = lastReport(targetId);
        long previous = before == null ? 0 : before.path("finishedAt").asLong();
        assertThat(env.api("POST", REALM, "targets/" + targetId + "/reconcile?dryRun=" + dryRun, null).status())
                .isEqualTo(202);
        await().atMost(WAIT).until(() -> {
            JsonNode report = lastReport(targetId);
            return report != null && report.path("finishedAt").asLong() > previous;
        });
        return lastReport(targetId);
    }

    private static void updateTarget(String targetId, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode config = (ObjectNode) env.api("GET", REALM, "targets/" + targetId, null).json();
        change.accept(config);
        assertThat(env.api("PUT", REALM, "targets/" + targetId, config.toString()).status()).isEqualTo(200);
    }

    @Test
    void existingUsersAreProvisionedAfterDryRun() {
        createUser("pre-1");
        createUser("pre-2");
        String targetId = env.createTarget(REALM, "late-target", null);

        JsonNode dry = reconcileAndWait(targetId, true);
        assertThat(dry.path("dryRun").asBoolean()).isTrue();
        assertThat(dry.path("userCreates").asInt()).as(dry.toString()).isGreaterThanOrEqualTo(2);
        assertThat(env.scim().find(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", matching("pre-.*"))))).isEmpty();
        assertThat(env.api("GET", REALM, "targets/" + targetId + "/status", null).json()
                .path("reconcileArmed").asBoolean()).isFalse();

        JsonNode real = reconcileAndWait(targetId, false);
        assertThat(real.path("dryRun").asBoolean()).isFalse();
        await().atMost(WAIT).untilAsserted(() -> {
            env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                    .withRequestBody(matchingJsonPath("$.userName", equalTo("pre-1"))));
            env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                    .withRequestBody(matchingJsonPath("$.userName", equalTo("pre-2"))));
        });
        assertThat(env.api("GET", REALM, "targets/" + targetId + "/status", null).json()
                .path("reconcileArmed").asBoolean()).isTrue();

        env.api("DELETE", REALM, "targets/" + targetId, null);
    }

    @Test
    void safetyBrakeStopsMassRemoval() {
        String targetId = env.createTarget(REALM, "brake", "http://scim:8080/brake/scim/v2",
                "\"safety\":{\"maxRemovalPercent\":5,\"removalFloor\":0}");
        String userId = createUser("oscar");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(
                urlEqualTo("/brake/scim/v2/Users")).withRequestBody(matchingJsonPath("$.userName", equalTo("oscar")))));

        // oscar is deleted while the target is disabled, so the deletion is never sent
        updateTarget(targetId, c -> c.put("enabled", false));
        env.admin().realm(REALM).users().get(userId).remove();
        updateTarget(targetId, c -> c.put("enabled", true));
        env.scim().register(get(urlPathEqualTo("/brake/scim/v2/Users")).atPriority(1)
                .withQueryParam("startIndex", matching(".*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/scim+json")
                        .withBody("{\"totalResults\":1,\"Resources\":[{\"id\":\"u-oscar\",\"userName\":\"oscar\","
                                + "\"active\":true}]}")));

        JsonNode blocked = reconcileAndWait(targetId, false);
        assertThat(blocked.path("blocked").asBoolean()).as(blocked.toString()).isTrue();
        assertThat(blocked.path("userRemovals").asInt()).isEqualTo(1);
        env.api("POST", REALM, "worker/run", null);
        assertThat(env.scim().find(anyRequestedFor(urlEqualTo("/brake/scim/v2/Users/u-oscar")))).isEmpty();

        // after review the admin raises the limit for one run
        updateTarget(targetId, c -> ((ObjectNode) c.path("safety")).put("maxRemovalPercent", 100));
        JsonNode allowed = reconcileAndWait(targetId, false);
        assertThat(allowed.path("blocked").asBoolean()).isFalse();
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/brake/scim/v2/Users/u-oscar"))
                .withRequestBody(matchingJsonPath("$.Operations[0].path", equalTo("active")))));
    }
}
