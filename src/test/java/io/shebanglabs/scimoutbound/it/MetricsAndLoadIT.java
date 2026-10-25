/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * A modest load test (many concurrent admin changes while the target is slow) and the metrics
 * exposed on Keycloak's management interface.
 */
class MetricsAndLoadIT {

    private static final String REALM = "load";
    private static final int USERS = 200;
    private static ScimEnvironment env;

    @BeforeAll
    static void start() {
        env = new ScimEnvironment("--spi-scim-outbound--default--batch-size=100");
        env.keycloak().withEnabledMetrics();
        env.start();
        env.createRealm(REALM);
        env.createTarget(REALM, "load-target", null);
    }

    @AfterAll
    static void stop() {
        if (env != null) {
            env.close();
        }
    }

    @Test
    void manyConcurrentChangesAreAllDeliveredAndAdminRequestsStayFast() throws Exception {
        ScimStubs.install(env.scim());
        // every create takes 50 ms on the target side
        env.scim().register(post("/scim/v2/Users").atPriority(1).willReturn(aResponse().withStatus(201)
                .withFixedDelay(50).withHeader("Content-Type", "application/scim+json")
                .withBody("{\"id\":\"u-{{jsonPath request.body '$.userName'}}\"}")));

        long started = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        IntStream.range(0, USERS).forEach(i -> pool.submit(() -> {
            UserRepresentation user = new UserRepresentation();
            user.setUsername("load-" + i);
            user.setEnabled(true);
            try (Response response = env.admin().realm(REALM).users().create(user)) {
                assertThat(response.getStatus()).isEqualTo(201);
            }
        }));
        pool.shutdown();
        assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
        double adminSeconds = (System.nanoTime() - started) / 1e9;
        // 200 creates at 50 ms each would take 10 s if requests waited for the target
        assertThat(adminSeconds).as("admin requests must not wait for SCIM").isLessThan(10);

        await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() ->
                assertThat(env.scim().find(postRequestedFor(urlEqualTo("/scim/v2/Users")))).hasSize(USERS));
    }

    @Test
    void metricsArePublished() throws Exception {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(env.keycloak().getMgmtServerUrl() + "/metrics")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("scim_outbound_queue_open")
                    .contains("scim_outbound_queue_oldest_age_seconds");
        });
    }
}
