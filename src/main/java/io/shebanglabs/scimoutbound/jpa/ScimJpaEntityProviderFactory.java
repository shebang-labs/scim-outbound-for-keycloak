/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import org.keycloak.Config;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Factory for {@link ScimJpaEntityProvider}.
 */
public class ScimJpaEntityProviderFactory implements JpaEntityProviderFactory {

    public static final String ID = "scim-outbound-entities";

    private static final ScimJpaEntityProvider INSTANCE = new ScimJpaEntityProvider();

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return INSTANCE;
    }

    @Override
    public void init(Config.Scope config) {
        // no options
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // nothing to do
    }

    @Override
    public void close() {
        // stateless
    }

    @Override
    public String getId() {
        return ID;
    }
}
