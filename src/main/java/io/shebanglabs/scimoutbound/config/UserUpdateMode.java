/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

/**
 * How existing users are updated and deactivated on a target.
 */
public enum UserUpdateMode {
    /**
     * PATCH when the target's {@code /ServiceProviderConfig} reports PATCH support, otherwise PUT.
     * A user PATCH answered with 400 is retried once as a PUT. The default.
     */
    AUTO,
    /** Always PATCH ({@code replace} operations with a {@code path}), never PUT. */
    PATCH,
    /**
     * Always PUT: the remote user is read, the mapped attributes are merged into it, and the
     * whole resource is sent back. For servers that reject the RFC 7644 path form of PATCH on
     * users, such as the 1Password SCIM Bridge.
     */
    PUT
}
