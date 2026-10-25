/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import java.util.List;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;

/**
 * Registers the plugin's entities and Liquibase changelog with Keycloak's JPA layer.
 */
public class ScimJpaEntityProvider implements JpaEntityProvider {

    /**
     * Ignored by the Quarkus distribution, which discovers {@code @Entity} classes in provider jars
     * at build time; still required by the interface.
     */
    @Override
    @SuppressWarnings("removal")
    public List<Class<?>> getEntities() {
        return List.of(ScimTargetEntity.class, OutboxEntity.class, IdMappingEntity.class);
    }

    @Override
    public String getChangelogLocation() {
        return "META-INF/scim-outbound-changelog.xml";
    }

    @Override
    public String getFactoryId() {
        return ScimJpaEntityProviderFactory.ID;
    }

    @Override
    public void close() {
        // stateless
    }
}
