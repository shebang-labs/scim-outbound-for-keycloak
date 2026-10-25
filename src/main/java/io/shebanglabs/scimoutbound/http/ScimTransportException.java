/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

/**
 * Network-level failure (timeout, connection refused, TLS error, oversized response). Always
 * retryable unless {@link #isPermanent()}.
 */
public class ScimTransportException extends Exception {

    private final boolean permanent;

    public ScimTransportException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
