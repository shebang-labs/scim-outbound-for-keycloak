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
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Default session-scoped provider.
 */
public class DefaultScimOutboundProvider implements ScimOutboundProvider {

    private static final Logger LOG = Logger.getLogger(DefaultScimOutboundProvider.class);

    private final KeycloakSession session;
    private final DefaultScimOutboundProviderFactory factory;
    private TargetRepository targets;
    private OutboxRepository outbox;
    private IdMappingRepository mappings;
    private ChangeRecorder recorder;

    public DefaultScimOutboundProvider(KeycloakSession session, DefaultScimOutboundProviderFactory factory) {
        this.session = session;
        this.factory = factory;
    }

    @Override
    public void record(RealmModel realm, Change change) {
        if (realm == null) {
            return;
        }
        try {
            ChangeRecorder r = recorder();
            switch (change) {
                case Change.UserChanged c -> r.userChanged(realm, c.userId());
                case Change.UserDeleted c -> r.userDeleted(realm, c.userId());
                case Change.GroupChanged c -> r.groupChanged(realm, c.groupId());
                case Change.GroupDeleted c -> r.groupDeleted(realm, c.groupId());
                case Change.MemberJoined c -> r.memberJoined(realm, c.groupId(), c.userId());
                case Change.MemberLeft c -> r.memberLeft(realm, c.groupId(), c.userId());
            }
        } catch (RuntimeException e) {
            // a provisioning problem must never break the admin action or login that caused it
            LOG.warnf("scim-outbound could not record %s: %s", change.getClass().getSimpleName(),
                    e.getClass().getSimpleName());
            LOG.debug("record failure", e);
        }
    }

    @Override
    public ChangeRecorder recorder() {
        if (recorder == null) {
            recorder = new ChangeRecorder(session, factory.serverConfig(), factory.clock(), targets(), outbox(),
                    mappings());
        }
        return recorder;
    }

    @Override
    public TargetRepository targets() {
        if (targets == null) {
            targets = new TargetRepository(em());
        }
        return targets;
    }

    @Override
    public OutboxRepository outbox() {
        if (outbox == null) {
            outbox = new OutboxRepository(em());
        }
        return outbox;
    }

    @Override
    public IdMappingRepository mappings() {
        if (mappings == null) {
            mappings = new IdMappingRepository(em());
        }
        return mappings;
    }

    private EntityManager em() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    @Override
    public void close() {
        // session-scoped, nothing to release
    }
}
