/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

/**
 * What an outbox row asks the worker to do. The row stores only ids; the worker reads current
 * data when it sends.
 */
public enum Operation {
    /** Create or update the object so it matches Keycloak (also handles disable and scope exit). */
    UPSERT,
    /** The object was deleted in Keycloak: deactivate or delete remotely. */
    DELETE,
    /** A user ({@code relatedId}) joined the group ({@code resourceId}). */
    MEMBER_ADD,
    /** A user ({@code relatedId}) left the group ({@code resourceId}). */
    MEMBER_REMOVE,
    /** Replace the remote member list of a group with Keycloak's. */
    SYNC_MEMBERS;

    /** Rows with this operation replace older pending rows with the same operation for the object. */
    public boolean coalesces() {
        return this == UPSERT || this == SYNC_MEMBERS;
    }

    public boolean isMembership() {
        return this == MEMBER_ADD || this == MEMBER_REMOVE;
    }
}
