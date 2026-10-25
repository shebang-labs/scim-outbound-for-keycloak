/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.it;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.testcontainers.images.PullPolicy;

/**
 * Shared Keycloak container configuration for integration tests.
 */
public final class KeycloakTestSupport {

    public static final String DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.8.1";

    private KeycloakTestSupport() {
    }

    public static String image() {
        return System.getProperty("keycloak.image", DEFAULT_IMAGE);
    }

    /** A Keycloak container with the plugin classes from {@code target/classes} loaded as a provider. */
    public static KeycloakContainer keycloakWithPlugin() {
        return new KeycloakContainer(image())
                // reuse a locally cached image instead of re-pulling it every 24 hours
                .withImagePullPolicy(PullPolicy.defaultPolicy())
                .withProviderClassesFrom("target/classes");
    }
}
