/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import org.keycloak.models.KeycloakSession;

/**
 * Default session-scoped provider.
 */
public class DefaultScimOutboundProvider implements ScimOutboundProvider {

    private final KeycloakSession session;

    public DefaultScimOutboundProvider(KeycloakSession session) {
        this.session = session;
    }

    KeycloakSession session() {
        return session;
    }

    @Override
    public void close() {
        // session-scoped, nothing to release
    }
}
