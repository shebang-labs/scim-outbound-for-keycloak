/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.shebanglabs.scimoutbound.scim.ScimGroup;
import io.shebanglabs.scimoutbound.scim.ScimMember;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;

class GroupMapperTest {

    @Test
    void mapsNameIdAndMembers() {
        GroupModel group = mock(GroupModel.class);
        when(group.getId()).thenReturn("g-1");
        when(group.getName()).thenReturn("engineering");

        ScimGroup scim = GroupMapper.toScim(group, List.of("r1", "r2"));

        assertThat(scim.schemas()).containsExactly(ScimSchemas.GROUP);
        assertThat(scim.externalId()).isEqualTo("g-1");
        assertThat(scim.displayName()).isEqualTo("engineering");
        assertThat(scim.members()).containsExactly(ScimMember.of("r1"), ScimMember.of("r2"));
    }

    @Test
    void membersCanBeOmitted() {
        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn("g");

        assertThat(GroupMapper.toScim(group, null).members()).isNull();
    }
}
