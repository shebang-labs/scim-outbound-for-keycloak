/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.config.TargetConfig;
import java.util.ArrayList;
import java.util.List;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Decides whether users and groups fall inside a target's scope. Scope groups are configured as
 * paths such as {@code /engineering}. If any of them cannot be resolved, the target is treated as
 * misconfigured instead of silently excluding (and deprovisioning) that group's members.
 */
public class ScopeEvaluator {

    private final boolean restricted;
    private final List<GroupModel> scopeGroups;
    private final List<String> missing;

    public ScopeEvaluator(KeycloakSession session, RealmModel realm, TargetConfig config) {
        this.restricted = config.scope() != null && config.scope().isRestricted();
        List<GroupModel> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String path : config.scopeGroups()) {
            GroupModel group = KeycloakModelUtils.findGroupByPath(session, realm, path);
            if (group == null) {
                notFound.add(path);
            } else {
                found.add(group);
            }
        }
        this.scopeGroups = List.copyOf(found);
        this.missing = List.copyOf(notFound);
    }

    ScopeEvaluator(boolean restricted, List<GroupModel> scopeGroups, List<String> missing) {
        this.restricted = restricted;
        this.scopeGroups = scopeGroups;
        this.missing = missing;
    }

    /**
     * A scope group that no longer exists (renamed, moved or deleted) makes every member of it
     * look out of scope, which would deprovision them all. Such a scope is treated as broken until
     * an admin fixes it, rather than as a smaller scope.
     */
    public boolean isMisconfigured() {
        return restricted && !missing.isEmpty();
    }

    /** Problem description for admins, or {@code null} when the scope is fine. */
    public String problem() {
        return isMisconfigured() ? "scope group(s) not found: " + String.join(", ", missing)
                + "; fix scope.groups (a renamed group keeps its members but changes its path)" : null;
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
