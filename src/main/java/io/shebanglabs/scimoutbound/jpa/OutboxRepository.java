/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.jpa;

import io.shebanglabs.scimoutbound.outbox.Operation;
import io.shebanglabs.scimoutbound.outbox.OutboxRow;
import io.shebanglabs.scimoutbound.outbox.OutboxStatus;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * Persistence for outbox rows: enqueue with coalescing, ordered claiming, and status updates.
 */
public class OutboxRepository {

    static final int MAX_ERROR = 1000;

    private final EntityManager em;

    public OutboxRepository(EntityManager em) {
        this.em = em;
    }

    /** Values describing a new row. */
    public record NewRow(String realmId, String targetId, ResourceType type, String resourceId, String relatedId,
            Operation operation, int priority) {
    }

    /**
     * Inserts a PENDING row. Older still-unclaimed rows that the new one makes redundant become
     * SUPERSEDED: a newer UPSERT or SYNC_MEMBERS replaces the same operation, a DELETE replaces
     * pending UPSERTs, and a membership change replaces the pending opposite change for the same
     * user and group.
     */
    public String enqueue(NewRow row, long now, long retryUntil) {
        Set<Operation> replaced = switch (row.operation()) {
            case UPSERT -> EnumSet.of(Operation.UPSERT);
            case SYNC_MEMBERS -> EnumSet.of(Operation.SYNC_MEMBERS);
            case DELETE -> EnumSet.of(Operation.UPSERT, Operation.DELETE, Operation.SYNC_MEMBERS);
            case MEMBER_ADD, MEMBER_REMOVE -> EnumSet.of(Operation.MEMBER_ADD, Operation.MEMBER_REMOVE);
        };
        String query = row.relatedId() == null ? "scimOutbox.supersede" : "scimOutbox.supersedeRelated";
        var update = em.createNamedQuery(query)
                .setParameter("superseded", OutboxStatus.SUPERSEDED)
                .setParameter("now", now)
                .setParameter("targetId", row.targetId())
                .setParameter("resourceType", row.type())
                .setParameter("resourceId", row.resourceId())
                .setParameter("operations", replaced)
                .setParameter("claimable", OutboxStatus.CLAIMABLE);
        if (row.relatedId() != null) {
            update.setParameter("relatedId", row.relatedId());
        }
        update.executeUpdate();

        OutboxEntity entity = new OutboxEntity();
        entity.setId(KeycloakModelUtils.generateId());
        entity.setRealmId(row.realmId());
        entity.setTargetId(row.targetId());
        entity.setResourceType(row.type());
        entity.setResourceId(row.resourceId());
        entity.setRelatedId(row.relatedId());
        entity.setOperation(row.operation());
        entity.setStatus(OutboxStatus.PENDING);
        entity.setPriority(row.priority());
        entity.setAttempts(0);
        entity.setNextAttemptAt(now);
        entity.setRetryUntil(retryUntil);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        em.persist(entity);
        return entity.getId();
    }

    /**
     * Finds due rows and claims up to {@code limit} of them atomically. At most one row per object
     * is claimed, and only when no older open row exists for that object, so changes to one user
     * or group are always applied in order.
     */
    public List<OutboxRow> claimDue(long now, int limit) {
        List<OutboxEntity> candidates = em.createNamedQuery("scimOutbox.due", OutboxEntity.class)
                .setParameter("statuses", OutboxStatus.CLAIMABLE)
                .setParameter("now", now)
                .setMaxResults(limit * 4)
                .getResultList();
        List<OutboxRow> claimed = new ArrayList<>();
        Set<String> seenObjects = new HashSet<>();
        for (OutboxEntity candidate : candidates) {
            if (claimed.size() >= limit) {
                break;
            }
            OutboxRow row = candidate.toRow();
            if (!seenObjects.add(row.objectKey()) || hasOlderOpenRow(candidate)) {
                continue;
            }
            int updated = em.createNamedQuery("scimOutbox.claim")
                    .setParameter("inProgress", OutboxStatus.IN_PROGRESS)
                    .setParameter("now", now)
                    .setParameter("id", candidate.getId())
                    .setParameter("claimable", OutboxStatus.CLAIMABLE)
                    .executeUpdate();
            if (updated == 1) {
                // the bulk update bypasses the persistence context: report the incremented count
                claimed.add(new OutboxRow(row.id(), row.realmId(), row.targetId(), row.resourceType(),
                        row.resourceId(), row.relatedId(), row.operation(), row.attempts() + 1, row.retryUntil()));
            }
        }
        return claimed;
    }

    private boolean hasOlderOpenRow(OutboxEntity candidate) {
        // membership rows are independent of each other and of the group's own rows
        if (candidate.getOperation().isMembership()) {
            return false;
        }
        Long older = em.createNamedQuery("scimOutbox.olderOpenForObject", Long.class)
                .setParameter("targetId", candidate.getTargetId())
                .setParameter("resourceType", candidate.getResourceType())
                .setParameter("resourceId", candidate.getResourceId())
                .setParameter("statuses", OutboxStatus.OPEN)
                .setParameter("createdAt", candidate.getCreatedAt())
                .setParameter("id", candidate.getId())
                .getSingleResult();
        return older > 0;
    }

    public void markDone(String id, long now) {
        update(id, OutboxStatus.DONE, null, null, now);
    }

    public void markSuperseded(String id, String reason, long now) {
        update(id, OutboxStatus.SUPERSEDED, reason, null, now);
    }

    public void markRetry(String id, long nextAttemptAt, String error, long now) {
        update(id, OutboxStatus.RETRY_WAIT, error, nextAttemptAt, now);
    }

    public void markFailed(String id, String error, long now) {
        update(id, OutboxStatus.FAILED, error, null, now);
    }

    private void update(String id, OutboxStatus status, String error, Long nextAttemptAt, long now) {
        OutboxEntity entity = em.find(OutboxEntity.class, id);
        if (entity == null) {
            return;
        }
        entity.setStatus(status);
        entity.setLastError(truncate(error));
        entity.setClaimedAt(null);
        entity.setUpdatedAt(now);
        if (nextAttemptAt != null) {
            entity.setNextAttemptAt(nextAttemptAt);
        }
    }

    /**
     * Gives claimed rows back without counting an attempt, for rows a worker decided not to
     * process in this round.
     */
    public int unclaim(List<String> ids, long nextAttemptAt, long now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return em.createNamedQuery("scimOutbox.unclaim")
                .setParameter("pending", OutboxStatus.PENDING)
                .setParameter("nextAttemptAt", nextAttemptAt)
                .setParameter("now", now)
                .setParameter("ids", ids)
                .setParameter("inProgress", OutboxStatus.IN_PROGRESS)
                .executeUpdate();
    }

    /** Returns IN_PROGRESS rows whose worker vanished (node crash) to PENDING. */
    public int releaseStale(long staleBefore, long now) {
        return em.createNamedQuery("scimOutbox.releaseStale")
                .setParameter("pending", OutboxStatus.PENDING)
                .setParameter("now", now)
                .setParameter("inProgress", OutboxStatus.IN_PROGRESS)
                .setParameter("staleBefore", staleBefore)
                .executeUpdate();
    }

    public int purge(Set<OutboxStatus> statuses, long before) {
        return em.createNamedQuery("scimOutbox.purge")
                .setParameter("statuses", statuses)
                .setParameter("before", before)
                .executeUpdate();
    }

    public Map<OutboxStatus, Long> countByStatus(String targetId) {
        Map<OutboxStatus, Long> counts = new EnumMap<>(OutboxStatus.class);
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status, 0L);
        }
        em.createNamedQuery("scimOutbox.countByStatus", Object[].class)
                .setParameter("targetId", targetId)
                .getResultStream()
                .forEach(r -> counts.put((OutboxStatus) r[0], ((Number) r[1]).longValue()));
        return counts;
    }

    public Optional<Long> oldestOpenCreatedAt(String targetId) {
        return Optional.ofNullable(em.createNamedQuery("scimOutbox.oldestOpen", Long.class)
                .setParameter("targetId", targetId)
                .setParameter("statuses", OutboxStatus.OPEN)
                .getSingleResult());
    }

    public Optional<Long> oldestOpenCreatedAtGlobal() {
        return Optional.ofNullable(em.createNamedQuery("scimOutbox.oldestOpenGlobal", Long.class)
                .setParameter("statuses", OutboxStatus.OPEN)
                .getSingleResult());
    }

    public long countOpenGlobal() {
        return em.createNamedQuery("scimOutbox.countOpenGlobal", Long.class)
                .setParameter("statuses", OutboxStatus.OPEN)
                .getSingleResult();
    }

    public List<OutboxEntity> failed(String targetId, int first, int max) {
        return em.createNamedQuery("scimOutbox.failedByTarget", OutboxEntity.class)
                .setParameter("targetId", targetId)
                .setParameter("failed", OutboxStatus.FAILED)
                .setFirstResult(first)
                .setMaxResults(max)
                .getResultList();
    }

    public Optional<OutboxEntity> get(String id) {
        return Optional.ofNullable(em.find(OutboxEntity.class, id));
    }

    /** Puts one FAILED row back to PENDING with a fresh retry window. */
    public boolean retry(String targetId, String rowId, long now, long retryUntil) {
        OutboxEntity entity = em.find(OutboxEntity.class, rowId);
        if (entity == null || !entity.getTargetId().equals(targetId) || entity.getStatus() != OutboxStatus.FAILED) {
            return false;
        }
        entity.setStatus(OutboxStatus.PENDING);
        entity.setAttempts(0);
        entity.setNextAttemptAt(now);
        entity.setRetryUntil(retryUntil);
        entity.setUpdatedAt(now);
        return true;
    }

    public int retryAllFailed(String targetId, long now, long retryUntil) {
        return em.createNamedQuery("scimOutbox.retryFailedByTarget")
                .setParameter("pending", OutboxStatus.PENDING)
                .setParameter("now", now)
                .setParameter("retryUntil", retryUntil)
                .setParameter("targetId", targetId)
                .setParameter("failed", OutboxStatus.FAILED)
                .executeUpdate();
    }

    public int deleteByTarget(String targetId) {
        return em.createNamedQuery("scimOutbox.deleteByTarget").setParameter("targetId", targetId).executeUpdate();
    }

    public int deleteByRealm(String realmId) {
        return em.createNamedQuery("scimOutbox.deleteByRealm").setParameter("realmId", realmId).executeUpdate();
    }

    static String truncate(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR - 3) + "...";
    }
}
