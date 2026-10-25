/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;

/**
 * Registers the {@code scim-outbound} SPI. It has a single built-in implementation
 * ({@code default}) that owns the server-wide state of the plugin: configuration,
 * the outbox worker, the reconciler and the HTTP client plumbing.
 *
 * <p>Server options are passed the usual Keycloak way, for example
 * {@code --spi-scim-outbound--default--allowed-hosts=*.example.com}.
 */
public class ScimOutboundSpi implements Spi {

    public static final String NAME = "scim-outbound";

    @Override
    public boolean isInternal() {
        return false;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Class<? extends Provider> getProviderClass() {
        return ScimOutboundProvider.class;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Class<? extends ProviderFactory> getProviderFactoryClass() {
        return ScimOutboundProviderFactory.class;
    }
}
