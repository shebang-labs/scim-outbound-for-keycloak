/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.TargetConfig;

/**
 * Admin API representation of a target: its (masked) configuration plus read-only metadata.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TargetView(
        String id,
        @JsonUnwrapped TargetConfig config,
        boolean reconcileArmed,
        Long lastReconcileAt,
        String lastReconcileSummary,
        long createdAt,
        long updatedAt) {

    public static TargetView of(ScimTarget target) {
        return new TargetView(target.id(), target.config().masked(), target.reconcileArmed(),
                target.lastReconcileAt(), target.lastReconcileSummary(), target.createdAt(), target.updatedAt());
    }
}
