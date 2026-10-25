/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.reconcile;

import io.shebanglabs.scimoutbound.mapping.UserMapper;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure comparison logic of reconciliation (design report figure 8). Given what Keycloak has,
 * what the target has, and the ID map, it decides what to queue. It never proposes to touch
 * remote accounts that this plugin did not create or link: accounts the application manages
 * itself are only counted.
 */
public final class ReconcilePlanner {

    /** A Keycloak user as seen by reconciliation. */
    public record LocalUser(String id, boolean inScope, ScimUser desired, String remoteId) {
    }

    /** A user mapping whose Keycloak user no longer exists. */
    public record OrphanMapping(String localId, String remoteId) {
    }

    /** Ids to queue, plus counters for the report. */
    public record UserPlan(
            List<String> upserts,
            List<String> deprovisionOutOfScope,
            List<String> deleteOrphans,
            int creates,
            int updates,
            int unmanagedRemote) {

        public int removals() {
            return deprovisionOutOfScope.size() + deleteOrphans.size();
        }
    }

    private ReconcilePlanner() {
    }

    public static UserPlan planUsers(List<LocalUser> locals, Map<String, ScimUser> remoteById,
            List<OrphanMapping> orphans) {
        List<String> upserts = new ArrayList<>();
        List<String> outOfScope = new ArrayList<>();
        Set<String> managedRemoteIds = new HashSet<>();
        int creates = 0;
        int updates = 0;
        for (LocalUser local : locals) {
            if (local.remoteId() != null) {
                managedRemoteIds.add(local.remoteId());
            }
            if (!local.inScope()) {
                ScimUser remote = local.remoteId() == null ? null : remoteById.get(local.remoteId());
                // only deactivate accounts that still exist and are still active
                if (remote != null && !Boolean.FALSE.equals(remote.active())) {
                    outOfScope.add(local.id());
                }
                continue;
            }
            if (local.remoteId() == null) {
                upserts.add(local.id());
                creates++;
                continue;
            }
            ScimUser remote = remoteById.get(local.remoteId());
            if (remote == null || !UserMapper.comparableDifferences(local.desired(), remote).isEmpty()) {
                upserts.add(local.id());
                updates++;
            }
        }
        List<String> deleteOrphans = new ArrayList<>();
        for (OrphanMapping orphan : orphans) {
            managedRemoteIds.add(orphan.remoteId());
            ScimUser remote = remoteById.get(orphan.remoteId());
            if (remote != null && !Boolean.FALSE.equals(remote.active())) {
                deleteOrphans.add(orphan.localId());
            }
        }
        int unmanaged = (int) remoteById.keySet().stream().filter(id -> !managedRemoteIds.contains(id)).count();
        return new UserPlan(upserts, outOfScope, deleteOrphans, creates, updates, unmanaged);
    }

    /**
     * Safety brake: removals are allowed up to {@code removalFloor}, beyond that only up to
     * {@code maxRemovalPercent} of the managed accounts.
     */
    public static boolean removalsBlocked(int removals, long managed, int maxRemovalPercent, int removalFloor) {
        if (removals <= removalFloor) {
            return false;
        }
        return removals * 100L > (long) maxRemovalPercent * Math.max(managed, 1);
    }
}
