/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.config.TargetConfig;
import java.util.List;
import java.util.Objects;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Decides whether users and groups fall inside a target's scope. Scope groups are configured as
 * paths such as {@code /engineering}. If a restricted scope cannot be resolved at all, the target
 * is treated as misconfigured instead of silently excluding (and deprovisioning) everybody.
 */
public class ScopeEvaluator {

    private final boolean restricted;
    private final List<GroupModel> scopeGroups;

    public ScopeEvaluator(KeycloakSession session, RealmModel realm, TargetConfig config) {
        this.restricted = config.scope() != null && config.scope().isRestricted();
        this.scopeGroups = config.scopeGroups().stream()
                .map(path -> KeycloakModelUtils.findGroupByPath(session, realm, path))
                .filter(Objects::nonNull)
                .toList();
    }

    ScopeEvaluator(boolean restricted, List<GroupModel> scopeGroups) {
        this.restricted = restricted;
        this.scopeGroups = scopeGroups;
    }

    /** {@code true} when the scope is restricted but none of its groups exist. */
    public boolean isMisconfigured() {
        return restricted && scopeGroups.isEmpty();
    }

    public boolean includes(UserModel user) {
        // isMemberOf also counts membership of a sub-group as membership of the parent
        return !restricted || scopeGroups.stream().anyMatch(user::isMemberOf);
    }

    public boolean includes(GroupModel group) {
        if (!restricted) {
            return true;
        }
        for (GroupModel g = group; g != null; g = g.getParent()) {
            String id = g.getId();
            if (scopeGroups.stream().anyMatch(s -> s.getId().equals(id))) {
                return true;
            }
        }
        return false;
    }
}
