/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

/**
 * A provisioning-relevant change extracted from a Keycloak event.
 */
public sealed interface Change {

    record UserChanged(String userId) implements Change {
    }

    record UserDeleted(String userId) implements Change {
    }

    record GroupChanged(String groupId) implements Change {
    }

    record GroupDeleted(String groupId) implements Change {
    }

    record MemberJoined(String groupId, String userId) implements Change {
    }

    record MemberLeft(String groupId, String userId) implements Change {
    }
}
