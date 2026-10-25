/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.auth;

import com.fasterxml.jackson.databind.JsonNode;
import io.shebanglabs.scimoutbound.http.ScimHttp;
import io.shebanglabs.scimoutbound.http.ScimResponse;
import io.shebanglabs.scimoutbound.http.ScimTransportException;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * OAuth 2.0 client credentials grant (RFC 6749 section 4.4) with client_secret_basic
 * authentication. Tokens are cached per target and refreshed shortly before expiry or once after
 * a 401.
 */
public class ClientCredentialsAuthenticator implements Authenticator {

    static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(5);

    private final ScimHttp http;
    private final TokenCache cache;
    private final String cacheKey;
    private final URI tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String scope;

    public ClientCredentialsAuthenticator(ScimHttp http, TokenCache cache, String cacheKey, URI tokenUrl,
            String clientId, String clientSecret, String scope) {
        this.http = http;
        this.cache = cache;
        this.cacheKey = cacheKey;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    @Override
    public boolean canRefresh() {
        return true;
    }

    @Override
    public String authorization(boolean refresh) throws AuthenticationException, ScimTransportException {
        if (refresh) {
            cache.evict(cacheKey);
        } else {
            var cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                return "Bearer " + cached.get();
            }
        }
        if (clientSecret == null) {
            throw new AuthenticationException("OAuth client secret is not available (check the vault entry)", false);
        }
        String form = "grant_type=client_credentials"
                + (scope == null || scope.isBlank() ? "" : "&scope=" + encode(scope));
        String basic = Base64.getEncoder().encodeToString(
                (encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
        ScimResponse response = http.sendForm(tokenUrl, form, Map.of("Authorization", "Basic " + basic));
        if (!response.isSuccess()) {
            boolean retryable = response.status() >= 500 || response.status() == 429;
            throw new AuthenticationException("token endpoint returned HTTP " + response.status(), retryable);
        }
        try {
            JsonNode json = ScimJson.mapper().readTree(response.body());
            String token = json.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new AuthenticationException("token endpoint response has no access_token", false);
            }
            long expiresIn = json.path("expires_in").asLong(0);
            Duration lifetime = expiresIn > 0 ? Duration.ofSeconds(expiresIn) : DEFAULT_LIFETIME;
            cache.put(cacheKey, token, lifetime);
            return "Bearer " + token;
        } catch (IOException e) {
            throw new AuthenticationException("token endpoint response is not valid JSON", false);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "ClientCredentialsAuthenticator[clientId=" + clientId + ", secret=***]";
    }
}
