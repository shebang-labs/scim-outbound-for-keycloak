/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Groups, membership, scoped targets, several targets and realm isolation.
 */
class GroupProvisioningIT {

    private static final String REALM = "groups";
    private static final String OTHER_REALM = "other";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    private static ScimEnvironment env;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment().start();
        env.createRealm(REALM);
        env.createRealm(OTHER_REALM);
        env.createTarget(REALM, "groups-target", "\"syncGroups\":true");
        // a second target in the same realm that only receives members of /sales and deletes on exit
        env.createTarget(REALM, "sales-only", "http://scim:8080/sales/scim/v2",
                "\"syncGroups\":true,\"deprovision\":\"DELETE\",\"scope\":{\"groups\":[\"/sales\"]}");
        // a target in another realm must never see this realm's changes
        env.createTarget(OTHER_REALM, "other-realm", "http://scim:8080/other/scim/v2", null);
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
        ScimStubs.installAt(env.scim(), "/sales/scim/v2");
        ScimStubs.installAt(env.scim(), "/other/scim/v2");
    }

    private static String createGroup(String name) {
        GroupRepresentation group = new GroupRepresentation();
        group.setName(name);
        try (Response response = env.admin().realm(REALM).groups().add(group)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    private static String createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(username + "@acme.test");
        user.setEnabled(true);
        try (Response response = env.admin().realm(REALM).users().create(user)) {
            return CreatedResponseUtil.getCreatedId(response);
        }
    }

    @Test
    void groupAndMembershipLifecycle() {
        String groupId = createGroup("engineering");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Groups"))
                .withRequestBody(matchingJsonPath("$.displayName", equalTo("engineering")))
                .withRequestBody(matchingJsonPath("$.externalId", equalTo(groupId)))));

        String userId = createUser("frank");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("frank")))));

        UserResource frank = env.admin().realm(REALM).users().get(userId);
        frank.joinGroup(groupId);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Groups/g-engineering")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":[{\"op\":\"add\",\"path\":\"members\","
                        + "\"value\":[{\"value\":\"u-frank\"}]}]}"))));

        frank.leaveGroup(groupId);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Groups/g-engineering")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":[{\"op\":\"remove\","
                        + "\"path\":\"members[value eq \\\"u-frank\\\"]\"}]}"))));

        GroupResource group = env.admin().realm(REALM).groups().group(groupId);
        GroupRepresentation rep = group.toRepresentation();
        rep.setName("platform");
        group.update(rep);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(patchRequestedFor(
                urlEqualTo("/scim/v2/Groups/g-engineering")).withRequestBody(equalToJson(
                "{\"schemas\":[\"" + PATCH_OP + "\"],\"Operations\":[{\"op\":\"replace\",\"path\":\"displayName\","
                        + "\"value\":\"platform\"}]}"))));

        group.remove();
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(
                deleteRequestedFor(urlEqualTo("/scim/v2/Groups/g-engineering"))));
    }

    @Test
    void scopedTargetOnlyReceivesScopeMembersAndDeletesOnExit() {
        String salesId = createGroup("sales");
        String userId = createUser("grace");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("grace")))));
        // grace is not in /sales yet: the scoped target must not have heard of her
        assertThat(env.scim().find(postRequestedFor(urlEqualTo("/sales/scim/v2/Users")))).isEmpty();

        UserResource grace = env.admin().realm(REALM).users().get(userId);
        grace.joinGroup(salesId);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(
                urlEqualTo("/sales/scim/v2/Users")).withRequestBody(matchingJsonPath("$.userName", equalTo("grace")))));

        grace.leaveGroup(salesId);
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(
                deleteRequestedFor(urlEqualTo("/sales/scim/v2/Users/u-grace"))));
    }

    @Test
    void otherRealmsTargetsAreNeverCalled() {
        createUser("heidi");
        await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(urlEqualTo("/scim/v2/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("heidi")))));

        assertThat(env.scim().find(com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                urlMatching("/other/.*")))).isEmpty();
    }
}
