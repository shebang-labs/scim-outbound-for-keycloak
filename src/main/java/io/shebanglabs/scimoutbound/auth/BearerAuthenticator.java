/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

/**
 * Static bearer token.
 */
public class BearerAuthenticator implements Authenticator {

    private final String token;

    public BearerAuthenticator(String token) {
        this.token = token;
    }

    @Override
    public String authorization(boolean refresh) throws AuthenticationException {
        if (token == null || token.isBlank()) {
            throw new AuthenticationException("bearer token is not available (check the vault entry)", false);
        }
        return "Bearer " + token;
    }

    @Override
    public String toString() {
        return "BearerAuthenticator[token=***]";
    }
}
