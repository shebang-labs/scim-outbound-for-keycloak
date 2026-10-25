/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.admin;

import io.shebanglabs.scimoutbound.spi.DefaultScimOutboundProviderFactory;
import io.shebanglabs.scimoutbound.spi.ScimOutboundProvider;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProvider;
import org.keycloak.services.resources.admin.ext.AdminRealmResourceProviderFactory;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Mounts {@link ScimAdminResource} under {@code /admin/realms/{realm}/scim-outbound}.
 */
public class ScimAdminResourceProviderFactory implements AdminRealmResourceProviderFactory, AdminRealmResourceProvider {

    public static final String ID = "scim-outbound";

    private DefaultScimOutboundProviderFactory pluginFactory;

    @Override
    public AdminRealmResourceProvider create(KeycloakSession session) {
        return this;
    }

    @Override
    public Object getResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
            AdminEventBuilder adminEvent) {
        return new ScimAdminResource(session, realm, auth, adminEvent, pluginFactory);
    }

    @Override
    public void init(Config.Scope config) {
        // no options
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        this.pluginFactory = (DefaultScimOutboundProviderFactory) factory.getProviderFactory(
                ScimOutboundProvider.class, DefaultScimOutboundProviderFactory.PROVIDER_ID);
    }

    @Override
    public void close() {
        // stateless
    }

    @Override
    public String getId() {
        return ID;
    }
}
