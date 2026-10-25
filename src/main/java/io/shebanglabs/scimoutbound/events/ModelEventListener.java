/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import io.shebanglabs.scimoutbound.spi.ScimOutboundProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;

/**
 * Catches model-level changes that do not always produce an admin event: user removal (also from
 * LDAP and account deletion), group creation, rename and removal (including LDAP group mappers),
 * and membership changes. Rows produced here and by the event listener for the same change are
 * coalesced in the outbox.
 */
public class ModelEventListener implements ProviderEventListener {

    @Override
    public void onEvent(ProviderEvent event) {
        switch (event) {
            case UserModel.UserRemovedEvent e ->
                    record(e.getKeycloakSession(), e.getRealm(), new Change.UserDeleted(e.getUser().getId()));
            case GroupModel.GroupMemberJoinEvent e -> record(e.getKeycloakSession(), e.getRealm(),
                    new Change.MemberJoined(e.getGroup().getId(), e.getUser().getId()));
            case GroupModel.GroupMemberLeaveEvent e -> record(e.getKeycloakSession(), e.getRealm(),
                    new Change.MemberLeft(e.getGroup().getId(), e.getUser().getId()));
            case GroupModel.GroupRemovedEvent e ->
                    record(e.getKeycloakSession(), e.getRealm(), new Change.GroupDeleted(e.getGroup().getId()));
            case GroupModel.GroupCreatedEvent e ->
                    record(e.getKeycloakSession(), e.getRealm(), new Change.GroupChanged(e.getGroup().getId()));
            case GroupModel.GroupUpdatedEvent e ->
                    record(e.getKeycloakSession(), e.getRealm(), new Change.GroupChanged(e.getGroup().getId()));
            case GroupModel.GroupPathChangeEvent e ->
                    record(e.getKeycloakSession(), e.getRealm(), new Change.GroupChanged(e.getGroup().getId()));
            case RealmModel.RealmRemovedEvent e -> realmRemoved(e.getKeycloakSession(), e.getRealm());
            default -> {
                // not relevant
            }
        }
    }

    private static void record(KeycloakSession session, RealmModel realm, Change change) {
        ScimOutboundProvider provider = session.getProvider(ScimOutboundProvider.class);
        if (provider != null) {
            provider.record(realm, change);
        }
    }

    private static void realmRemoved(KeycloakSession session, RealmModel realm) {
        ScimOutboundProvider provider = session.getProvider(ScimOutboundProvider.class);
        if (provider != null) {
            provider.recorder().realmRemoved(realm.getId());
        }
    }
}
