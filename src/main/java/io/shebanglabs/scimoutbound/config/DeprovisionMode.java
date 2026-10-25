/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

/**
 * What happens remotely when a user is deleted in Keycloak or leaves the target's scope.
 */
public enum DeprovisionMode {
    /** {@code PATCH active=false}. Reversible; the default. */
    DEACTIVATE,
    /** {@code DELETE /Users/{id}}. Must be chosen explicitly. */
    DELETE
}
