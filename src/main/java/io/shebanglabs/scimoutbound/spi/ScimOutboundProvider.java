/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import org.keycloak.provider.Provider;

/**
 * Session-scoped entry point to the plugin. Other providers (event listener, admin
 * REST resource) obtain it with {@code session.getProvider(ScimOutboundProvider.class)}.
 */
public interface ScimOutboundProvider extends Provider {
}
