/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;

/**
 * A Keycloak container with the plugin, plus a WireMock container acting as the SCIM target on a
 * shared Docker network (reachable from Keycloak as {@code http://scim:8080}).
 */
public final class ScimEnvironment implements AutoCloseable {

    public static final String WIREMOCK_IMAGE = "wiremock/wiremock:3.13.1";
    public static final String SCIM_BASE = "http://scim:8080/scim/v2";

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> wiremock;
    private final KeycloakContainer keycloak;
    private final HttpClient http = HttpClient.newHttpClient();
    private WireMock scim;
    private Keycloak admin;

    public ScimEnvironment(String... extraOptions) {
        wiremock = new GenericContainer<>(WIREMOCK_IMAGE)
                .withImagePullPolicy(PullPolicy.defaultPolicy())
                .withNetwork(network)
                .withNetworkAliases("scim")
                .withExposedPorts(8080)
                .withCommand("--global-response-templating", "--disable-banner")
                .waitingFor(Wait.forHttp("/__admin/health").forPort(8080));
        List<String> options = new java.util.ArrayList<>(List.of(
                // the WireMock target is plain http on a private Docker network
                "--spi-scim-outbound--default--allow-http=true",
                "--spi-scim-outbound--default--allow-private-addresses=true",
                "--spi-scim-outbound--default--allow-plaintext-secrets=true",
                "--spi-scim-outbound--default--worker-interval-seconds=1"));
        options.addAll(List.of(extraOptions));
        keycloak = KeycloakTestSupport.keycloakWithPlugin().withNetwork(network);
        // each call adds exactly one command-line argument
        options.forEach(keycloak::withCustomCommand);
    }

    public ScimEnvironment start() {
        wiremock.start();
        keycloak.start();
        scim = new WireMock(wiremock.getHost(), wiremock.getMappedPort(8080));
        admin = keycloak.getKeycloakAdminClient();
        return this;
    }

    public WireMock scim() {
        return scim;
    }

    public Keycloak admin() {
        return admin;
    }

    public KeycloakContainer keycloak() {
        return keycloak;
    }

    /** Creates a realm with the scim-outbound event listener enabled. */
    public void createRealm(String name) {
        RealmRepresentation realm = new RealmRepresentation();
        realm.setRealm(name);
        realm.setEnabled(true);
        realm.setEventsListeners(List.of("jboss-logging", "scim-outbound"));
        admin.realms().create(realm);
    }

    // ---- plugin admin REST -------------------------------------------------------------------

    public record ApiResponse(int status, String body) {
        public JsonNode json() {
            try {
                return ScimJson.mapper().readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("not JSON: " + body, e);
            }
        }
    }

    public ApiResponse api(String method, String realm, String path, String body) {
        String url = keycloak.getAuthServerUrl() + "/admin/realms/" + realm + "/scim-outbound/" + path;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + admin.tokenManager().getAccessTokenString())
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Creates a bearer-token target pointing at WireMock and returns its id. */
    public String createTarget(String realm, String name, String extraJson) {
        return createTarget(realm, name, SCIM_BASE, extraJson);
    }

    /** Creates a bearer-token target with an explicit base URL and returns its id. */
    public String createTarget(String realm, String name, String baseUrl, String extraJson) {
        String body = "{\"name\":\"" + name + "\",\"baseUrl\":\"" + baseUrl + "\","
                + "\"auth\":{\"type\":\"BEARER\",\"token\":\"test-token\"}"
                + (extraJson == null || extraJson.isBlank() ? "" : "," + extraJson) + "}";
        ApiResponse response = api("POST", realm, "targets", body);
        if (response.status() != 201) {
            throw new IllegalStateException("target creation failed: " + response);
        }
        return response.json().path("id").asText();
    }

    @Override
    public void close() {
        if (admin != null) {
            admin.close();
        }
        // dev mode only logs some errors that production mode turns into HTTP 500s (for example a
        // response without a media type), so the log is checked before the container goes away
        String logs = keycloak.isRunning() ? keycloak.getLogs() : "";
        keycloak.stop();
        wiremock.stop();
        network.close();
        for (String marker : List.of("Uncaught server error", "MediaType not set")) {
            if (logs.contains(marker)) {
                throw new AssertionError("Keycloak logged '" + marker + "':\n"
                        + logs.substring(Math.max(0, logs.indexOf(marker) - 300),
                                Math.min(logs.length(), logs.indexOf(marker) + 1500)));
            }
        }
    }
}
