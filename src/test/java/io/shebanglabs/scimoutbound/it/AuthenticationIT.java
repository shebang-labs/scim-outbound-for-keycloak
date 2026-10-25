/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.images.builder.Transferable;

/**
 * Vault-referenced secrets, OAuth2 client credentials and HTTP Basic against a real Keycloak.
 */
class AuthenticationIT {

    private static final String REALM = "auth";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static ScimEnvironment env;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment("--vault=file", "--vault-dir=/opt/keycloak/vault");
        // the files vault resolves ${vault.<key>} in realm <realm> from the file <realm>_<key>
        env.keycloak()
                .withCopyToContainer(Transferable.of("token-from-vault"), "/opt/keycloak/vault/auth_scim-token")
                .withCopyToContainer(Transferable.of("oauth-secret"), "/opt/keycloak/vault/auth_oauth-secret");
        env.start();
        env.createRealm(REALM);
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
        ScimStubs.installAt(env.scim(), "/vault/scim/v2");
        ScimStubs.installAt(env.scim(), "/oauth/scim/v2");
        ScimStubs.installAt(env.scim(), "/basic/scim/v2");
        env.scim().register(post("/oauth/token").willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"oauth-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}")));
    }

    private static void createUser(String username) {
        UserRepresentation user = new UserRepresentation();
        user.setUsername(username);
        user.setEnabled(true);
        try (Response response = env.admin().realm(REALM).users().create(user)) {
            assertThat(response.getStatus()).isEqualTo(201);
        }
    }

    private static void createTarget(String name, String base, String auth) {
        String body = "{\"name\":\"" + name + "\",\"baseUrl\":\"http://scim:8080" + base + "\",\"auth\":" + auth + "}";
        assertThat(env.api("POST", REALM, "targets", body).status()).isEqualTo(201);
    }

    @Test
    void bearerTokenIsResolvedFromVault() {
        createTarget("vault", "/vault/scim/v2", "{\"type\":\"BEARER\",\"token\":\"${vault.scim-token}\"}");
        try {
            createUser("ivan");

            await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(
                    urlEqualTo("/vault/scim/v2/Users"))
                    .withHeader("Authorization", equalTo("Bearer token-from-vault"))
                    .withRequestBody(matchingJsonPath("$.userName", equalTo("ivan")))));
            assertThat(env.api("GET", REALM, "targets", null).body()).doesNotContain("token-from-vault");
        } finally {
            deleteAllTargets();
        }
    }

    @Test
    void oauthClientCredentialsTokenIsFetchedAndUsed() {
        createTarget("oauth", "/oauth/scim/v2", "{\"type\":\"OAUTH2_CLIENT_CREDENTIALS\","
                + "\"tokenUrl\":\"http://scim:8080/oauth/token\",\"clientId\":\"keycloak\","
                + "\"clientSecret\":\"${vault.oauth-secret}\",\"scope\":\"scim.write\"}");
        try {
            createUser("judy");

            await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(
                    urlEqualTo("/oauth/scim/v2/Users"))
                    .withHeader("Authorization", equalTo("Bearer oauth-access-token"))));
            env.scim().verifyThat(postRequestedFor(urlEqualTo("/oauth/token"))
                    // base64("keycloak:oauth-secret")
                    .withHeader("Authorization", equalTo("Basic a2V5Y2xvYWs6b2F1dGgtc2VjcmV0"))
                    .withRequestBody(containing("grant_type=client_credentials")));
        } finally {
            deleteAllTargets();
        }
    }

    @Test
    void basicAuthIsSent() {
        createTarget("basic", "/basic/scim/v2", "{\"type\":\"BASIC\",\"username\":\"svc\",\"password\":\"pw\"}");
        try {
            createUser("mallory");

            await().atMost(WAIT).untilAsserted(() -> env.scim().verifyThat(postRequestedFor(
                    urlEqualTo("/basic/scim/v2/Users"))
                    .withHeader("Authorization", equalTo("Basic c3ZjOnB3"))));
        } finally {
            deleteAllTargets();
        }
    }

    private static void deleteAllTargets() {
        env.api("GET", REALM, "targets", null).json()
                .forEach(t -> env.api("DELETE", REALM, "targets/" + t.path("id").asText(), null));
    }
}
