/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Built-in implementation of the {@code scim-outbound} SPI.
 */
public class DefaultScimOutboundProviderFactory implements ScimOutboundProviderFactory {

    public static final String PROVIDER_ID = "default";

    private static final Logger LOG = Logger.getLogger(DefaultScimOutboundProviderFactory.class);

    @Override
    public ScimOutboundProvider create(KeycloakSession session) {
        return new DefaultScimOutboundProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        LOG.debug("scim-outbound initialised");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // background services are wired here in later steps
    }

    @Override
    public void close() {
        // nothing to release yet
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
