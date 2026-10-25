/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import io.shebanglabs.scimoutbound.events.Change;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.jpa.OutboxRepository;
import io.shebanglabs.scimoutbound.jpa.TargetRepository;
import io.shebanglabs.scimoutbound.outbox.ChangeRecorder;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.Provider;

/**
 * Session-scoped entry point to the plugin. Other providers (event listener, admin REST
 * resource) obtain it with {@code session.getProvider(ScimOutboundProvider.class)}. All methods
 * work inside the session's current transaction.
 */
public interface ScimOutboundProvider extends Provider {

    /** Records a change for every interested target of the realm. Never throws. */
    void record(RealmModel realm, Change change);

    ChangeRecorder recorder();

    TargetRepository targets();

    OutboxRepository outbox();

    IdMappingRepository mappings();
}
