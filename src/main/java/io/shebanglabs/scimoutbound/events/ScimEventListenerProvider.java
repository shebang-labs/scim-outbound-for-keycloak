/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;

/**
 * Receives user and admin events. It never performs network I/O: relevant changes are only
 * recorded in the outbox inside the current transaction.
 */
public class ScimEventListenerProvider implements EventListenerProvider {

    private final KeycloakSession session;

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        // wired to the outbox in a later step
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        // wired to the outbox in a later step
    }

    KeycloakSession session() {
        return session;
    }

    @Override
    public void close() {
        // session-scoped, nothing to release
    }
}
