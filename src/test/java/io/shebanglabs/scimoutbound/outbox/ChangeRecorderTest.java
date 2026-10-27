/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.ServerConfig;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.jpa.IdMappingRepository;
import io.shebanglabs.scimoutbound.jpa.OutboxRepository;
import io.shebanglabs.scimoutbound.jpa.TargetRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

class ChangeRecorderTest {

    /** Shape of a user from a federation provider without import. */
    private static final String FEDERATED_ID = "f:" + "6a4f9c2e-1b7d-4e8a-9c21-7d4e5f6a8b90" + ":"
            + "cn=alice,ou=people,dc=example,dc=com".repeat(8);
    private static final String LONGEST_ID = "x".repeat(ChangeRecorder.MAX_ID_LENGTH);

    private final TargetRepository targets = mock(TargetRepository.class);
    private final OutboxRepository outbox = mock(OutboxRepository.class);
    private final RealmModel realm = mock(RealmModel.class);
    private ChangeRecorder recorder;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);
        recorder = new ChangeRecorder(mock(KeycloakSession.class), ServerConfig.defaults(), clock, targets, outbox,
                mock(IdMappingRepository.class));
        when(realm.getId()).thenReturn("realm-1");
        TargetConfig config = new TargetConfig("app", true, "https://app.example.com/scim/v2", null, null, true,
                null, null, null, null);
        when(targets.listEnabledByRealm("realm-1"))
                .thenReturn(List.of(new ScimTarget("target-1", "realm-1", config, false, null, null, 0, 0)));
    }

    @Test
    void skipsIdsTooLongToStoreInsteadOfFailingTheTransaction() {
        assertThat(FEDERATED_ID).hasSizeGreaterThan(ChangeRecorder.MAX_ID_LENGTH);

        recorder.userDeleted(realm, FEDERATED_ID);
        recorder.memberJoined(realm, "group-1", FEDERATED_ID);

        verifyNoInteractions(outbox);
    }

    @Test
    void recordsIdsUpToTheColumnLength() {
        recorder.userDeleted(realm, LONGEST_ID);

        ArgumentCaptor<OutboxRepository.NewRow> row = ArgumentCaptor.forClass(OutboxRepository.NewRow.class);
        verify(outbox).enqueue(row.capture(), anyLong(), anyLong());
        assertThat(row.getValue().resourceId()).isEqualTo(LONGEST_ID);
        assertThat(row.getValue().operation()).isEqualTo(Operation.DELETE);
    }

    @Test
    void storableChecksBothIds() {
        assertThat(ChangeRecorder.storable("u1", null, ResourceType.USER, Operation.UPSERT)).isTrue();
        assertThat(ChangeRecorder.storable("g1", "u1", ResourceType.GROUP, Operation.MEMBER_ADD)).isTrue();
        assertThat(ChangeRecorder.storable(FEDERATED_ID, null, ResourceType.USER, Operation.UPSERT)).isFalse();
        assertThat(ChangeRecorder.storable("g1", FEDERATED_ID, ResourceType.GROUP, Operation.MEMBER_ADD)).isFalse();
        assertThat(ChangeRecorder.storable(null, null, ResourceType.USER, Operation.UPSERT)).isFalse();
    }
}
