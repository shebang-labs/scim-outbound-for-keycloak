/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import java.util.List;

/**
 * Everything the executor needs to know about one group, loaded in a short read transaction.
 *
 * @param displayName     the group name, or {@code null} if the group no longer exists
 * @param inScope         whether the target's scope includes the group
 * @param remoteId        the mapped remote id, or {@code null}
 * @param memberRemoteIds remote ids of the group's direct, in-scope, already provisioned members
 */
public record GroupState(String groupId, String displayName, boolean inScope, String remoteId,
        List<String> memberRemoteIds) {

    public static GroupState gone(String groupId, String remoteId) {
        return new GroupState(groupId, null, false, remoteId, List.of());
    }

    public boolean exists() {
        return displayName != null;
    }
}
