/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import java.util.Optional;
import org.keycloak.models.KeycloakSession;
import org.keycloak.vault.VaultStringSecret;

/**
 * Turns a stored secret ({@code ${vault.key}} reference or, if allowed, a literal) into its value.
 */
@FunctionalInterface
public interface SecretResolver {

    Optional<String> resolve(String stored);

    /**
     * Resolver backed by Keycloak's vault. The session's realm must be set, because vault keys are
     * realm-scoped. Literal values pass through unchanged (Keycloak's own behaviour).
     */
    static SecretResolver vault(KeycloakSession session) {
        return stored -> {
            if (stored == null) {
                return Optional.empty();
            }
            try (VaultStringSecret secret = session.vault().getStringSecret(stored)) {
                return secret.get();
            }
        };
    }
}
