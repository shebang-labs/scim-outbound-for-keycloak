/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.spi;

import org.keycloak.provider.ProviderFactory;

/**
 * Factory type of the {@code scim-outbound} SPI.
 */
public interface ScimOutboundProviderFactory extends ProviderFactory<ScimOutboundProvider> {
}
