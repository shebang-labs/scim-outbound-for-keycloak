/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import io.shebanglabs.scimoutbound.spi.ScimOutboundProvider;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Receives user and admin events. It never performs network I/O: relevant changes are only
 * recorded in the outbox inside the current transaction, so they commit or roll back together
 * with the change itself.
 */
public class ScimEventListenerProvider implements EventListenerProvider {

    private final KeycloakSession session;

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        EventInterpreter.fromUserEvent(event.getType(), event.getUserId(), event.getError())
                .ifPresent(change -> record(event.getRealmId(), change));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        EventInterpreter.fromAdminEvent(event.getResourceType(), event.getOperationType(), event.getResourcePath(),
                includeRepresentation ? event.getRepresentation() : null, event.getError())
                .ifPresent(change -> record(event.getRealmId(), change));
    }

    private void record(String realmId, Change change) {
        RealmModel realm = realmId == null ? null : session.realms().getRealm(realmId);
        ScimOutboundProvider provider = session.getProvider(ScimOutboundProvider.class);
        if (provider != null) {
            provider.record(realm, change);
        }
    }

    @Override
    public void close() {
        // session-scoped, nothing to release
    }
}
