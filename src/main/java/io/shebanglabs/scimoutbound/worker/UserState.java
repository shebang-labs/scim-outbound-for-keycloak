/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.List;

/**
 * Everything the executor needs to know about one user, loaded in a short read transaction.
 *
 * @param desired        the SCIM representation of the user now, or {@code null} if the user no
 *                       longer exists in Keycloak
 * @param inScope        whether the target's scope includes the user
 * @param remoteId       the mapped remote id, or {@code null}
 * @param groupRemoteIds remote ids of the synced, in-scope groups the user is a direct member of
 */
public record UserState(String userId, ScimUser desired, boolean inScope, String remoteId,
        List<String> groupRemoteIds) {

    public static UserState gone(String userId, String remoteId) {
        return new UserState(userId, null, false, remoteId, List.of());
    }

    public boolean exists() {
        return desired != null;
    }

    public UserState withRemoteId(String id) {
        return new UserState(userId, desired, inScope, id, groupRemoteIds);
    }
}
