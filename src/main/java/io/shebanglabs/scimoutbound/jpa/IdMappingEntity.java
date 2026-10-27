/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import io.shebanglabs.scimoutbound.outbox.ResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

/**
 * Keycloak id to remote SCIM id, per target. Never shared between targets or realms.
 */
@Entity
@Table(name = "SCIM_OUTBOUND_ID_MAP")
@NamedQueries({
    @NamedQuery(name = "scimIdMap.byLocal",
            query = "select m from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType and m.localId = :localId"),
    @NamedQuery(name = "scimIdMap.byLocalIds",
            query = "select m from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType and m.localId in :localIds"),
    @NamedQuery(name = "scimIdMap.byRemote",
            query = "select m from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType and m.remoteId = :remoteId"),
    @NamedQuery(name = "scimIdMap.byTarget",
            query = "select m from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType order by m.localId"),
    @NamedQuery(name = "scimIdMap.countByTarget",
            query = "select count(m) from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType"),
    @NamedQuery(name = "scimIdMap.deleteByLocal",
            query = "delete from IdMappingEntity m where m.targetId = :targetId"
                    + " and m.resourceType = :resourceType and m.localId = :localId"),
    @NamedQuery(name = "scimIdMap.deleteByTarget",
            query = "delete from IdMappingEntity m where m.targetId = :targetId"),
    @NamedQuery(name = "scimIdMap.deleteByRealm",
            query = "delete from IdMappingEntity m where m.realmId = :realmId")
})
public class IdMappingEntity {

    @Id
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "REALM_ID", length = 36, nullable = false)
    private String realmId;

    @Column(name = "TARGET_ID", length = 36, nullable = false)
    private String targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "RESOURCE_TYPE", length = 16, nullable = false)
    private ResourceType resourceType;

    @Column(name = "LOCAL_ID", length = 255, nullable = false)
    private String localId;

    @Column(name = "REMOTE_ID", length = 255, nullable = false)
    private String remoteId;

    @Column(name = "CREATED_AT", nullable = false)
    private long createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private long updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public void setResourceType(ResourceType resourceType) {
        this.resourceType = resourceType;
    }

    public String getLocalId() {
        return localId;
    }

    public void setLocalId(String localId) {
        this.localId = localId;
    }

    public String getRemoteId() {
        return remoteId;
    }

    public void setRemoteId(String remoteId) {
        this.remoteId = remoteId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
