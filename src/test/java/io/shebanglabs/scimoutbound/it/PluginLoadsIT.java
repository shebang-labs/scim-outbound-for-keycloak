/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import static org.assertj.core.api.Assertions.assertThat;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.info.ProviderRepresentation;
import org.keycloak.representations.info.SpiInfoRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PluginLoadsIT {

    @Container
    static final KeycloakContainer KEYCLOAK = KeycloakTestSupport.keycloakWithPlugin();

    @Test
    void keycloakLoadsSpiAndEventListener() {
        try (Keycloak admin = KEYCLOAK.getKeycloakAdminClient()) {
            Map<String, SpiInfoRepresentation> providers = admin.serverInfo().getInfo().getProviders();

            assertThat(providers).containsKey("scim-outbound");
            Map<String, ProviderRepresentation> ours = providers.get("scim-outbound").getProviders();
            assertThat(ours).containsKey("default");

            assertThat(providers.get("eventsListener").getProviders()).containsKey("scim-outbound");
        }
    }
}
