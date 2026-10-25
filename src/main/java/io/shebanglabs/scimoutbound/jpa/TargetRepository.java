/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import io.shebanglabs.scimoutbound.config.InvalidConfigurationException;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Persistence for {@link ScimTarget}s.
 */
public class TargetRepository {

    static final int MAX_CONFIG_LENGTH = 4000;

    private final EntityManager em;

    public TargetRepository(EntityManager em) {
        this.em = em;
    }

    public List<ScimTarget> listByRealm(String realmId) {
        return em.createNamedQuery("scimTarget.byRealm", ScimTargetEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream().map(TargetRepository::toModel).toList();
    }

    public List<ScimTarget> listEnabledByRealm(String realmId) {
        return em.createNamedQuery("scimTarget.enabledByRealm", ScimTargetEntity.class)
                .setParameter("realmId", realmId)
                .getResultStream().map(TargetRepository::toModel).toList();
    }

    public List<ScimTarget> listAllEnabled() {
        return em.createNamedQuery("scimTarget.allEnabled", ScimTargetEntity.class)
                .getResultStream().map(TargetRepository::toModel).toList();
    }

    public Optional<ScimTarget> get(String targetId) {
        return Optional.ofNullable(em.find(ScimTargetEntity.class, targetId)).map(TargetRepository::toModel);
    }

    /** Returns the target only if it belongs to the realm (admin API isolation). */
    public Optional<ScimTarget> get(String realmId, String targetId) {
        return get(targetId).filter(t -> t.realmId().equals(realmId));
    }

    public ScimTarget create(String realmId, TargetConfig config, long now) {
        ScimTargetEntity entity = new ScimTargetEntity();
        entity.setId(KeycloakModelUtils.generateId());
        entity.setRealmId(realmId);
        entity.setCreatedAt(now);
        apply(entity, config, now);
        em.persist(entity);
        return toModel(entity);
    }

    public Optional<ScimTarget> update(String realmId, String targetId, TargetConfig config, long now) {
        ScimTargetEntity entity = em.find(ScimTargetEntity.class, targetId);
        if (entity == null || !entity.getRealmId().equals(realmId)) {
            return Optional.empty();
        }
        apply(entity, config, now);
        return Optional.of(toModel(entity));
    }

    public void markReconciled(String targetId, boolean armed, String summary, long now) {
        ScimTargetEntity entity = em.find(ScimTargetEntity.class, targetId);
        if (entity == null) {
            return;
        }
        entity.setReconcileArmed(entity.isReconcileArmed() || armed);
        entity.setLastReconcileAt(now);
        entity.setLastReconcileSummary(truncate(summary));
    }

    public boolean delete(String realmId, String targetId) {
        ScimTargetEntity entity = em.find(ScimTargetEntity.class, targetId);
        if (entity == null || !entity.getRealmId().equals(realmId)) {
            return false;
        }
        em.remove(entity);
        return true;
    }

    public int deleteByRealm(String realmId) {
        return em.createNamedQuery("scimTarget.deleteByRealm").setParameter("realmId", realmId).executeUpdate();
    }

    private static void apply(ScimTargetEntity entity, TargetConfig config, long now) {
        String json = ScimJson.write(config);
        if (json.length() > MAX_CONFIG_LENGTH) {
            throw new InvalidConfigurationException("configuration is too large (max " + MAX_CONFIG_LENGTH
                    + " characters of JSON)");
        }
        entity.setName(config.name());
        entity.setEnabled(config.enabledOrDefault());
        entity.setConfig(json);
        entity.setUpdatedAt(now);
    }

    static ScimTarget toModel(ScimTargetEntity e) {
        try {
            TargetConfig config = ScimJson.read(e.getConfig(), TargetConfig.class);
            return new ScimTarget(e.getId(), e.getRealmId(), config, e.isReconcileArmed(), e.getLastReconcileAt(),
                    e.getLastReconcileSummary(), e.getCreatedAt(), e.getUpdatedAt());
        } catch (IOException ex) {
            throw new UncheckedIOException("stored configuration of target " + e.getId() + " is not valid JSON", ex);
        }
    }

    private static String truncate(String s) {
        return s == null || s.length() <= MAX_CONFIG_LENGTH ? s : s.substring(0, MAX_CONFIG_LENGTH);
    }
}
