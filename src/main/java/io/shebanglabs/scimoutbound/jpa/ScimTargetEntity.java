/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

/**
 * One SCIM target of a realm. The editable settings are stored as JSON in {@code CONFIG}
 * (secrets inside are vault references); a few columns are kept separately for querying.
 */
@Entity
@Table(name = "SCIM_OUTBOUND_TARGET")
@NamedQueries({
    @NamedQuery(name = "scimTarget.byRealm",
            query = "select t from ScimTargetEntity t where t.realmId = :realmId order by t.name"),
    @NamedQuery(name = "scimTarget.enabledByRealm",
            query = "select t from ScimTargetEntity t where t.realmId = :realmId and t.enabled = true"),
    @NamedQuery(name = "scimTarget.allEnabled",
            query = "select t from ScimTargetEntity t where t.enabled = true"),
    @NamedQuery(name = "scimTarget.deleteByRealm",
            query = "delete from ScimTargetEntity t where t.realmId = :realmId")
})
public class ScimTargetEntity {

    @Id
    @Column(name = "ID", length = 36)
    private String id;

    @Column(name = "REALM_ID", length = 36, nullable = false)
    private String realmId;

    @Column(name = "NAME", length = 64, nullable = false)
    private String name;

    @Column(name = "ENABLED", nullable = false)
    private boolean enabled;

    @Column(name = "CONFIG", length = 4000, nullable = false)
    private String config;

    @Column(name = "RECONCILE_ARMED", nullable = false)
    private boolean reconcileArmed;

    @Column(name = "LAST_RECONCILE_AT")
    private Long lastReconcileAt;

    @Column(name = "LAST_RECONCILE_SUMMARY", length = 4000)
    private String lastReconcileSummary;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public boolean isReconcileArmed() {
        return reconcileArmed;
    }

    public void setReconcileArmed(boolean reconcileArmed) {
        this.reconcileArmed = reconcileArmed;
    }

    public Long getLastReconcileAt() {
        return lastReconcileAt;
    }

    public void setLastReconcileAt(Long lastReconcileAt) {
        this.lastReconcileAt = lastReconcileAt;
    }

    public String getLastReconcileSummary() {
        return lastReconcileSummary;
    }

    public void setLastReconcileSummary(String lastReconcileSummary) {
        this.lastReconcileSummary = lastReconcileSummary;
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
