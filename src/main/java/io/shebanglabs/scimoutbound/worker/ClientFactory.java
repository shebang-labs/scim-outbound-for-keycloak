/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.worker;

import io.shebanglabs.scimoutbound.auth.Authenticator;
import io.shebanglabs.scimoutbound.auth.BasicAuthenticator;
import io.shebanglabs.scimoutbound.auth.BearerAuthenticator;
import io.shebanglabs.scimoutbound.auth.ClientCredentialsAuthenticator;
import io.shebanglabs.scimoutbound.auth.SecretResolver;
import io.shebanglabs.scimoutbound.auth.TokenCache;
import io.shebanglabs.scimoutbound.config.HostPolicy;
import io.shebanglabs.scimoutbound.config.InvalidConfigurationException;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.config.TargetValidator;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimHttp;
import java.net.URI;

/**
 * Builds a {@link ScimClient} for a target: re-checks its URLs against the host policy (the
 * operator may have tightened it, or DNS may have changed) and resolves secrets at the moment of
 * use. Resolved secrets only live in the returned client object.
 */
public class ClientFactory {

    private final HostPolicy hostPolicy;
    private final ServerConfig serverConfig;
    private final ScimHttp http;
    private final TokenCache tokenCache;

    public ClientFactory(HostPolicy hostPolicy, ServerConfig serverConfig, ScimHttp http, TokenCache tokenCache) {
        this.hostPolicy = hostPolicy;
        this.serverConfig = serverConfig;
        this.http = http;
        this.tokenCache = tokenCache;
    }

    /**
     * @throws InvalidConfigurationException if a URL is no longer permitted or auth is incomplete
     */
    public ScimClient create(ScimTarget target, SecretResolver secrets) {
        TargetConfig config = target.config();
        URI baseUrl = hostPolicy.check(config.baseUrl());
        return new ScimClient(http, baseUrl, authenticator(target, secrets));
    }

    private Authenticator authenticator(ScimTarget target, SecretResolver secrets) {
        TargetConfig.Auth auth = target.config().auth();
        if (auth == null || auth.type() == null) {
            throw new InvalidConfigurationException("target has no authentication configured");
        }
        for (String secret : new String[] {auth.token(), auth.password(), auth.clientSecret()}) {
            TargetValidator.requireAllowedVaultKey(serverConfig, secret);
        }
        return switch (auth.type()) {
            case BEARER -> new BearerAuthenticator(secrets.resolve(auth.token()).orElse(null));
            case BASIC -> new BasicAuthenticator(auth.username(), secrets.resolve(auth.password()).orElse(null));
            case OAUTH2_CLIENT_CREDENTIALS -> new ClientCredentialsAuthenticator(http, tokenCache,
                    cacheKey(target), hostPolicy.check(auth.tokenUrl()), auth.clientId(),
                    secrets.resolve(auth.clientSecret()).orElse(null), auth.scope());
        };
    }

    /** A config change produces a new key, so stale tokens are never reused. */
    static String cacheKey(ScimTarget target) {
        return target.id() + ":" + target.updatedAt();
    }

    public void evict(String targetId) {
        tokenCache.evictPrefix(targetId + ":");
    }
}
