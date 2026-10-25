/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

/**
 * A stored target: its identity, realm, editable configuration and reconciliation state.
 *
 * @param reconcileArmed {@code false} until an admin has run one non-dry reconciliation; until
 *                       then scheduled reconciliation only reports differences
 */
public record ScimTarget(
        String id,
        String realmId,
        TargetConfig config,
        boolean reconcileArmed,
        Long lastReconcileAt,
        String lastReconcileSummary,
        long createdAt,
        long updatedAt) {

    public String name() {
        return config.name();
    }

    public boolean enabled() {
        return config.enabledOrDefault();
    }
}
