/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.mapping.UserMapper;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import java.util.List;
import java.util.Map;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Reads the current Keycloak state of users and groups for one target, inside a short read-only
 * transaction. Everything returned is a plain value safe to use after the transaction ends.
 */
public class SyncLoader {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final ScimTarget target;
    private final IdMappingRepository mappings;
    private final ScopeEvaluator scope;
    private final UserMapper mapper;

    public SyncLoader(KeycloakSession session, RealmModel realm, ScimTarget target, IdMappingRepository mappings) {
        this.session = session;
        this.realm = realm;
        this.target = target;
        this.mappings = mappings;
        this.scope = new ScopeEvaluator(session, realm, target.config());
        this.mapper = new UserMapper(target.config());
    }

    public ScopeEvaluator scope() {
        return scope;
    }

    public UserState user(String userId) {
        String remoteId = mappings.remoteId(target.id(), ResourceType.USER, userId).orElse(null);
        UserModel user = session.users().getUserById(realm, userId);
        if (user == null) {
            return UserState.gone(userId, remoteId);
        }
        return user(user, remoteId);
    }

    public UserState user(UserModel user, String remoteId) {
        List<String> groupRemoteIds = List.of();
        if (target.config().syncGroupsOrDefault()) {
            List<String> groupIds = user.getGroupsStream().filter(scope::includes).map(GroupModel::getId).toList();
            groupRemoteIds = List.copyOf(mappings.remoteIds(target.id(), ResourceType.GROUP, groupIds).values());
        }
        return new UserState(user.getId(), mapper.toScim(user), scope.includes(user), remoteId, groupRemoteIds);
    }

    public GroupState group(String groupId) {
        String remoteId = mappings.remoteId(target.id(), ResourceType.GROUP, groupId).orElse(null);
        GroupModel group = session.groups().getGroupById(realm, groupId);
        if (group == null) {
            return GroupState.gone(groupId, remoteId);
        }
        return group(group, remoteId);
    }

    public GroupState group(GroupModel group, String remoteId) {
        boolean inScope = scope.includes(group);
        List<String> memberRemoteIds = List.of();
        if (inScope) {
            List<String> memberIds = session.users().getGroupMembersStream(realm, group)
                    .filter(scope::includes)
                    .map(UserModel::getId)
                    .toList();
            Map<String, String> remote = mappings.remoteIds(target.id(), ResourceType.USER, memberIds);
            memberRemoteIds = memberIds.stream().map(remote::get).filter(id -> id != null).toList();
        }
        return new GroupState(group.getId(), group.getName(), inScope, remoteId, memberRemoteIds);
    }

    public String userRemoteId(String userId) {
        return mappings.remoteId(target.id(), ResourceType.USER, userId).orElse(null);
    }
}
