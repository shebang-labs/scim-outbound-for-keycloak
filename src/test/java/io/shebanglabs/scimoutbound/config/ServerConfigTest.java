/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServerConfigTest {

    @Test
    void defaultsAreSecure() {
        ServerConfig config = ServerConfig.defaults();

        assertThat(config.allowHttp()).isFalse();
        assertThat(config.allowPrivateAddresses()).isFalse();
        assertThat(config.allowPlaintextSecrets()).isFalse();
        assertThat(config.allowedHosts()).isEmpty();
        assertThat(config.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxResponseBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(config.maxRetryAge()).isEqualTo(Duration.ofHours(24));
        assertThat(config.reconcileInterval()).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void readsOverridesFromLookup() {
        Map<String, String> values = Map.of(
                ServerConfig.ALLOWED_HOSTS, " App.Example.com , *.saas.test ,",
                ServerConfig.ALLOW_HTTP, "true",
                ServerConfig.BATCH_SIZE, "10",
                ServerConfig.WORKER_INTERVAL_SECONDS, "1",
                ServerConfig.RECONCILE_INTERVAL_MINUTES, "0");

        ServerConfig config = ServerConfig.fromLookup(values::get);

        assertThat(config.allowedHosts()).containsExactly("app.example.com", "*.saas.test");
        assertThat(config.allowHttp()).isTrue();
        assertThat(config.batchSize()).isEqualTo(10);
        assertThat(config.workerInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.reconcileInterval()).isZero();
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> ServerConfig.fromLookup(Map.of(ServerConfig.BATCH_SIZE, "lots")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServerConfig.fromLookup(Map.of(ServerConfig.BATCH_SIZE, "0")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServerConfig.fromLookup(Map.of(ServerConfig.READ_TIMEOUT_MS, "-1")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
