/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.mapping;

import io.shebanglabs.scimoutbound.scim.ScimGroup;
import io.shebanglabs.scimoutbound.scim.ScimMember;
import java.util.List;
import org.keycloak.models.GroupModel;

/**
 * Converts a Keycloak group into a SCIM Group. The remote {@code displayName} is the Keycloak
 * group name; {@code externalId} is the Keycloak group id. Members are given as remote user ids.
 */
public final class GroupMapper {

    private GroupMapper() {
    }

    public static ScimGroup toScim(GroupModel group, List<String> memberRemoteIds) {
        List<ScimMember> members = memberRemoteIds == null
                ? null
                : memberRemoteIds.stream().map(ScimMember::of).toList();
        return ScimGroup.create(group.getId(), group.getName(), members);
    }
}
