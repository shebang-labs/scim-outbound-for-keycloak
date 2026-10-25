/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.reconcile;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Outcome of one reconciliation run. Contains counts only, no personal data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReconcileReport(
        String targetId,
        boolean dryRun,
        boolean blocked,
        int localUsers,
        int remoteUsers,
        int userCreates,
        int userUpdates,
        int userRemovals,
        int unmanagedRemoteUsers,
        int groupUpserts,
        int groupRemovals,
        long startedAt,
        long finishedAt,
        String message) {

    public static ReconcileReport failed(String targetId, boolean dryRun, long startedAt, long finishedAt,
            String message) {
        return new ReconcileReport(targetId, dryRun, false, 0, 0, 0, 0, 0, 0, 0, 0, startedAt, finishedAt, message);
    }
}
