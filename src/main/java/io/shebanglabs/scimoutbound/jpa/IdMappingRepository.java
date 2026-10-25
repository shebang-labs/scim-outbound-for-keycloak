/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import io.shebanglabs.scimoutbound.outbox.ResourceType;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Persistence for the Keycloak id to remote id mapping.
 */
public class IdMappingRepository {

    /** Bound for IN-clauses so large groups do not hit database parameter limits. */
    static final int IN_CHUNK = 500;

    private final EntityManager em;

    public IdMappingRepository(EntityManager em) {
        this.em = em;
    }

    public Optional<String> remoteId(String targetId, ResourceType type, String localId) {
        return find(targetId, type, localId).map(IdMappingEntity::getRemoteId);
    }

    /** Remote ids for many local ids; unmapped ids are absent from the result. */
    public Map<String, String> remoteIds(String targetId, ResourceType type, Collection<String> localIds) {
        Map<String, String> result = new HashMap<>();
        List<String> all = List.copyOf(localIds);
        for (int i = 0; i < all.size(); i += IN_CHUNK) {
            List<String> chunk = all.subList(i, Math.min(all.size(), i + IN_CHUNK));
            em.createNamedQuery("scimIdMap.byLocalIds", IdMappingEntity.class)
                    .setParameter("targetId", targetId)
                    .setParameter("resourceType", type)
                    .setParameter("localIds", chunk)
                    .getResultStream()
                    .forEach(m -> result.put(m.getLocalId(), m.getRemoteId()));
        }
        return result;
    }

    public Optional<String> localId(String targetId, ResourceType type, String remoteId) {
        return em.createNamedQuery("scimIdMap.byRemote", IdMappingEntity.class)
                .setParameter("targetId", targetId)
                .setParameter("resourceType", type)
                .setParameter("remoteId", remoteId)
                .getResultStream().findFirst().map(IdMappingEntity::getLocalId);
    }

    public void put(String realmId, String targetId, ResourceType type, String localId, String remoteId, long now) {
        Optional<IdMappingEntity> existing = find(targetId, type, localId);
        if (existing.isPresent()) {
            existing.get().setRemoteId(remoteId);
            existing.get().setUpdatedAt(now);
            return;
        }
        IdMappingEntity entity = new IdMappingEntity();
        entity.setId(KeycloakModelUtils.generateId());
        entity.setRealmId(realmId);
        entity.setTargetId(targetId);
        entity.setResourceType(type);
        entity.setLocalId(localId);
        entity.setRemoteId(remoteId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        em.persist(entity);
    }

    public void remove(String targetId, ResourceType type, String localId) {
        em.createNamedQuery("scimIdMap.deleteByLocal")
                .setParameter("targetId", targetId)
                .setParameter("resourceType", type)
                .setParameter("localId", localId)
                .executeUpdate();
    }

    /** Page of mappings ordered by local id, for reconciliation. */
    public List<IdMappingEntity> page(String targetId, ResourceType type, int first, int max) {
        return em.createNamedQuery("scimIdMap.byTarget", IdMappingEntity.class)
                .setParameter("targetId", targetId)
                .setParameter("resourceType", type)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList();
    }

    public long count(String targetId, ResourceType type) {
        return em.createNamedQuery("scimIdMap.countByTarget", Long.class)
                .setParameter("targetId", targetId)
                .setParameter("resourceType", type)
                .getSingleResult();
    }

    public int deleteByTarget(String targetId) {
        return em.createNamedQuery("scimIdMap.deleteByTarget").setParameter("targetId", targetId).executeUpdate();
    }

    public int deleteByRealm(String realmId) {
        return em.createNamedQuery("scimIdMap.deleteByRealm").setParameter("realmId", realmId).executeUpdate();
    }

    private Optional<IdMappingEntity> find(String targetId, ResourceType type, String localId) {
        return em.createNamedQuery("scimIdMap.byLocal", IdMappingEntity.class)
                .setParameter("targetId", targetId)
                .setParameter("resourceType", type)
                .setParameter("localId", localId)
                .getResultStream().findFirst();
    }
}
