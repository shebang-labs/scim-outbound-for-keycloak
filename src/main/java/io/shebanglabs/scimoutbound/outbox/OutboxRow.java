/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

/**
 * Immutable snapshot of a claimed outbox row, safe to use outside a transaction.
 *
 * @param relatedId for membership rows, the user id; otherwise {@code null}
 * @param attempts  attempts including the current one
 * @param retryUntil epoch millis after which a failing row becomes FAILED
 */
public record OutboxRow(
        String id,
        String realmId,
        String targetId,
        ResourceType resourceType,
        String resourceId,
        String relatedId,
        Operation operation,
        int attempts,
        long retryUntil) {

    /**
     * Key that defines processing order: rows with the same key run oldest first, one at a time.
     * Membership rows are keyed per (group, user) pair so many joins to one group do not queue up
     * behind each other.
     */
    public String objectKey() {
        String key = targetId + "|" + resourceType + "|" + resourceId;
        return operation.isMembership() ? key + "|" + relatedId : key;
    }
}
