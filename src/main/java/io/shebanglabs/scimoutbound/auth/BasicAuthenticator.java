/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * HTTP Basic authentication (RFC 7617).
 */
public class BasicAuthenticator implements Authenticator {

    private final String username;
    private final String password;

    public BasicAuthenticator(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public String authorization(boolean refresh) throws AuthenticationException {
        if (username == null || password == null) {
            throw new AuthenticationException("basic credentials are not available (check the vault entry)", false);
        }
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "BasicAuthenticator[username=" + username + ", password=***]";
    }
}
