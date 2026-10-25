/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;

class EventInterpreterTest {

    @Test
    void userEvents() {
        assertThat(EventInterpreter.fromUserEvent(EventType.REGISTER, "u1", null))
                .contains(new Change.UserChanged("u1"));
        assertThat(EventInterpreter.fromUserEvent(EventType.UPDATE_PROFILE, "u1", null))
                .contains(new Change.UserChanged("u1"));
        assertThat(EventInterpreter.fromUserEvent(EventType.UPDATE_EMAIL, "u1", null))
                .contains(new Change.UserChanged("u1"));
        assertThat(EventInterpreter.fromUserEvent(EventType.DELETE_ACCOUNT, "u1", null))
                .contains(new Change.UserDeleted("u1"));
    }

    @Test
    void ignoresIrrelevantOrFailedUserEvents() {
        assertThat(EventInterpreter.fromUserEvent(EventType.LOGIN, "u1", null)).isEmpty();
        assertThat(EventInterpreter.fromUserEvent(EventType.REGISTER, "u1", "invalid_registration")).isEmpty();
        assertThat(EventInterpreter.fromUserEvent(EventType.REGISTER, null, null)).isEmpty();
    }

    @Test
    void adminUserEvents() {
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.CREATE, "users/u1", null, null))
                .contains(new Change.UserChanged("u1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.UPDATE, "users/u1", null, null))
                .contains(new Change.UserChanged("u1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.DELETE, "users/u1", null, null))
                .contains(new Change.UserDeleted("u1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.ACTION,
                "users/u1/execute-actions-email", null, null)).contains(new Change.UserChanged("u1"));
    }

    @Test
    void adminMembershipEvents() {
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP_MEMBERSHIP, OperationType.CREATE,
                "users/u1/groups/g1", null, null)).contains(new Change.MemberJoined("g1", "u1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP_MEMBERSHIP, OperationType.DELETE,
                "users/u1/groups/g1", null, null)).contains(new Change.MemberLeft("g1", "u1"));
    }

    @Test
    void adminGroupEvents() {
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP, OperationType.CREATE, "groups/g1", null, null))
                .contains(new Change.GroupChanged("g1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP, OperationType.UPDATE, "groups/g1", null, null))
                .contains(new Change.GroupChanged("g1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP, OperationType.DELETE, "groups/g1", null, null))
                .contains(new Change.GroupDeleted("g1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP, OperationType.CREATE, "groups/p/children",
                "{\"id\":\"c1\",\"name\":\"child\"}", null)).contains(new Change.GroupChanged("c1"));
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.GROUP, OperationType.CREATE, "groups/p/children",
                null, null)).isEmpty();
    }

    @Test
    void ignoresOtherResourcesAndErrors() {
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.CLIENT, OperationType.CREATE, "clients/c1", null,
                null)).isEmpty();
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.CREATE, "users/u1", null,
                "boom")).isEmpty();
        assertThat(EventInterpreter.fromAdminEvent(ResourceType.USER, OperationType.UPDATE, "users/u1/credentials/x",
                null, null)).isEmpty();
    }
}
