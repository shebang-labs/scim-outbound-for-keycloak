/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Event listener that realm admins enable under <em>Realm settings → Events → Event listeners</em>.
 */
public class ScimEventListenerProviderFactory implements EventListenerProviderFactory {

    public static final String PROVIDER_ID = "scim-outbound";

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new ScimEventListenerProvider(session);
    }

    @Override
    public void init(Config.Scope config) {
        // configuration lives on the scim-outbound SPI
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // nothing to do
    }

    @Override
    public void close() {
        // nothing to release
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
