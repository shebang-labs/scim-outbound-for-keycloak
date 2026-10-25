/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.LocalUser;
import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.OrphanMapping;
import io.shebanglabs.scimoutbound.reconcile.ReconcilePlanner.UserPlan;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReconcilePlannerTest {

    private static ScimUser user(String userName, boolean active) {
        return new ScimUser(null, null, null, userName, null, null, null, null, null, null, active, null);
    }

    private static ScimUser remote(String id, String userName, boolean active) {
        return user(userName, active).withId(id);
    }

    @Test
    void missingRemotelyIsCreated() {
        UserPlan plan = ReconcilePlanner.planUsers(
                List.of(new LocalUser("kc-1", true, user("a", true), null)), Map.of(), List.of());

        assertThat(plan.upserts()).containsExactly("kc-1");
        assertThat(plan.creates()).isEqualTo(1);
        assertThat(plan.removals()).isZero();
    }

    @Test
    void driftIsUpdatedAndEqualUsersAreLeftAlone() {
        UserPlan plan = ReconcilePlanner.planUsers(List.of(
                        new LocalUser("same", true, user("a", true), "r-a"),
                        new LocalUser("drift", true, user("b", false), "r-b"),
                        new LocalUser("gone-remote", true, user("c", true), "r-c")),
                Map.of("r-a", remote("r-a", "a", true), "r-b", remote("r-b", "b", true)), List.of());

        assertThat(plan.upserts()).containsExactly("drift", "gone-remote");
        assertThat(plan.updates()).isEqualTo(2);
    }

    @Test
    void outOfScopeActiveAccountsAreDeprovisioned() {
        UserPlan plan = ReconcilePlanner.planUsers(List.of(
                        new LocalUser("left", false, user("a", true), "r-a"),
                        new LocalUser("already-off", false, user("b", true), "r-b"),
                        new LocalUser("never-sent", false, user("c", true), null)),
                Map.of("r-a", remote("r-a", "a", true), "r-b", remote("r-b", "b", false)), List.of());

        assertThat(plan.deprovisionOutOfScope()).containsExactly("left");
        assertThat(plan.upserts()).isEmpty();
    }

    @Test
    void orphanedMappingsAreRemovedButUnmanagedRemoteAccountsAreNeverTouched() {
        UserPlan plan = ReconcilePlanner.planUsers(List.of(),
                Map.of("r-orphan", remote("r-orphan", "x", true),
                        "r-app-owned", remote("r-app-owned", "y", true)),
                List.of(new OrphanMapping("deleted-kc-user", "r-orphan")));

        assertThat(plan.deleteOrphans()).containsExactly("deleted-kc-user");
        assertThat(plan.unmanagedRemote()).isEqualTo(1);
    }

    @Test
    void safetyBrake() {
        // up to the floor everything is allowed
        assertThat(ReconcilePlanner.removalsBlocked(10, 10, 5, 10)).isFalse();
        // beyond the floor, at most maxRemovalPercent of managed accounts
        assertThat(ReconcilePlanner.removalsBlocked(11, 1000, 5, 10)).isFalse();
        assertThat(ReconcilePlanner.removalsBlocked(51, 1000, 5, 10)).isTrue();
        assertThat(ReconcilePlanner.removalsBlocked(11, 11, 5, 10)).isTrue();
        // an empty or broken Keycloak side cannot wipe a large target
        assertThat(ReconcilePlanner.removalsBlocked(5000, 5000, 5, 10)).isTrue();
        assertThat(ReconcilePlanner.removalsBlocked(1, 1, 0, 0)).isTrue();
    }
}
