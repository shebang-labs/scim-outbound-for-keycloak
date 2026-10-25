/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

/**
 * Credentials could not be obtained (missing vault secret, token endpoint refused the client).
 * Messages never include secret values.
 */
public class AuthenticationException extends Exception {

    private final boolean retryable;

    public AuthenticationException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
