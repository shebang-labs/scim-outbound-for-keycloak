/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import io.shebanglabs.scimoutbound.outbox.Operation;
import io.shebanglabs.scimoutbound.outbox.OutboxRow;
import io.shebanglabs.scimoutbound.outbox.OutboxStatus;
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
 * A durable unit of outgoing work. It holds ids only, never personal data; the error column
 * stores a sanitised, truncated summary.
 */
@Entity
@Table(name = "SCIM_OUTBOUND_OUTBOX")
@NamedQueries({
    @NamedQuery(name = "scimOutbox.due",
            query = "select o from OutboxEntity o where o.status in :statuses and o.nextAttemptAt <= :now"
                    + " order by o.priority, o.createdAt"),
    @NamedQuery(name = "scimOutbox.olderOpenForObject",
            query = "select count(o) from OutboxEntity o where o.targetId = :targetId"
                    + " and o.resourceType = :resourceType and o.resourceId = :resourceId"
                    + " and o.status in :statuses and o.createdAt < :createdAt and o.id <> :id"),
    @NamedQuery(name = "scimOutbox.claim",
            query = "update OutboxEntity o set o.status = :inProgress, o.claimedAt = :now,"
                    + " o.attempts = o.attempts + 1, o.updatedAt = :now"
                    + " where o.id = :id and o.status in :claimable"),
    @NamedQuery(name = "scimOutbox.supersede",
            query = "update OutboxEntity o set o.status = :superseded, o.updatedAt = :now"
                    + " where o.targetId = :targetId and o.resourceType = :resourceType"
                    + " and o.resourceId = :resourceId and o.operation in :operations"
                    + " and o.relatedId is null and o.status in :claimable"),
    @NamedQuery(name = "scimOutbox.supersedeRelated",
            query = "update OutboxEntity o set o.status = :superseded, o.updatedAt = :now"
                    + " where o.targetId = :targetId and o.resourceType = :resourceType"
                    + " and o.resourceId = :resourceId and o.operation in :operations"
                    + " and o.relatedId = :relatedId and o.status in :claimable"),
    @NamedQuery(name = "scimOutbox.unclaim",
            query = "update OutboxEntity o set o.status = :pending, o.claimedAt = null, o.attempts = o.attempts - 1,"
                    + " o.nextAttemptAt = :nextAttemptAt, o.updatedAt = :now"
                    + " where o.id in :ids and o.status = :inProgress"),
    @NamedQuery(name = "scimOutbox.releaseStale",
            query = "update OutboxEntity o set o.status = :pending, o.claimedAt = null, o.updatedAt = :now"
                    + " where o.status = :inProgress and o.claimedAt < :staleBefore"),
    @NamedQuery(name = "scimOutbox.purge",
            query = "delete from OutboxEntity o where o.status in :statuses and o.updatedAt < :before"),
    @NamedQuery(name = "scimOutbox.countByStatus",
            query = "select o.status, count(o) from OutboxEntity o where o.targetId = :targetId group by o.status"),
    @NamedQuery(name = "scimOutbox.oldestOpen",
            query = "select min(o.createdAt) from OutboxEntity o where o.targetId = :targetId"
                    + " and o.status in :statuses"),
    @NamedQuery(name = "scimOutbox.oldestOpenGlobal",
            query = "select min(o.createdAt) from OutboxEntity o where o.status in :statuses"),
    @NamedQuery(name = "scimOutbox.countOpenGlobal",
            query = "select count(o) from OutboxEntity o where o.status in :statuses"),
    @NamedQuery(name = "scimOutbox.failedByTarget",
            query = "select o from OutboxEntity o where o.targetId = :targetId and o.status = :failed"
                    + " order by o.updatedAt desc"),
    @NamedQuery(name = "scimOutbox.retryFailedByTarget",
            query = "update OutboxEntity o set o.status = :pending, o.nextAttemptAt = :now,"
                    + " o.retryUntil = :retryUntil, o.attempts = 0, o.updatedAt = :now"
                    + " where o.targetId = :targetId and o.status = :failed"),
    @NamedQuery(name = "scimOutbox.deleteByTarget",
            query = "delete from OutboxEntity o where o.targetId = :targetId"),
    @NamedQuery(name = "scimOutbox.deleteByRealm",
            query = "delete from OutboxEntity o where o.realmId = :realmId")
})
public class OutboxEntity {

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

    @Column(name = "RESOURCE_ID", length = 255, nullable = false)
    private String resourceId;

    @Column(name = "RELATED_ID", length = 255)
    private String relatedId;

    @Enumerated(EnumType.STRING)
    @Column(name = "OPERATION", length = 32, nullable = false)
    private Operation operation;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", length = 16, nullable = false)
    private OutboxStatus status;

    @Column(name = "PRIORITY", nullable = false)
    private int priority;

    @Column(name = "ATTEMPTS", nullable = false)
    private int attempts;

    @Column(name = "NEXT_ATTEMPT_AT", nullable = false)
    private long nextAttemptAt;

    @Column(name = "RETRY_UNTIL", nullable = false)
    private long retryUntil;

    @Column(name = "CLAIMED_AT")
    private Long claimedAt;

    @Column(name = "CREATED_AT", nullable = false)
    private long createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private long updatedAt;

    @Column(name = "LAST_ERROR", length = 1000)
    private String lastError;

    public OutboxRow toRow() {
        return new OutboxRow(id, realmId, targetId, resourceType, resourceId, relatedId, operation, attempts,
                retryUntil);
    }

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

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getRelatedId() {
        return relatedId;
    }

    public void setRelatedId(String relatedId) {
        this.relatedId = relatedId;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public void setStatus(OutboxStatus status) {
        this.status = status;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public long getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(long nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public long getRetryUntil() {
        return retryUntil;
    }

    public void setRetryUntil(long retryUntil) {
        this.retryUntil = retryUntil;
    }

    public Long getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Long claimedAt) {
        this.claimedAt = claimedAt;
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

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
