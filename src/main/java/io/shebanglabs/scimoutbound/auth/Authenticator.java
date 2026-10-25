/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import io.shebanglabs.scimoutbound.http.ScimTransportException;

/**
 * Produces the {@code Authorization} header for requests to one target.
 */
public interface Authenticator {

    /**
     * @param refresh {@code true} after a 401, to discard any cached credential first
     * @return the header value, e.g. {@code Bearer abc}
     */
    String authorization(boolean refresh) throws AuthenticationException, ScimTransportException;

    /** Whether {@link #authorization(boolean) refresh} can produce a different credential. */
    default boolean canRefresh() {
        return false;
    }
}
