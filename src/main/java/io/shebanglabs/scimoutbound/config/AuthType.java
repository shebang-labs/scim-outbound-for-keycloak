/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

/**
 * How the plugin authenticates to a SCIM target.
 */
public enum AuthType {
    /** Static token sent as {@code Authorization: Bearer}. */
    BEARER,
    /** OAuth 2.0 client credentials grant; tokens are cached and refreshed. */
    OAUTH2_CLIENT_CREDENTIALS,
    /** HTTP Basic. Only for legacy servers. */
    BASIC
}
