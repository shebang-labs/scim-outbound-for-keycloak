/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.scim.ScimEmail;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimName;
import io.shebanglabs.scimoutbound.scim.ScimPatchOp;
import io.shebanglabs.scimoutbound.scim.ScimSchemas;
import io.shebanglabs.scimoutbound.scim.ScimUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;
import org.mockito.Mockito;

class UserMapperTest {

    private static TargetConfig config(Map<String, String> custom) {
        return new TargetConfig("t", null, "https://x", null, null, null, null, custom, null, null, null);
    }

    private static UserModel alice() {
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("kc-1");
        when(user.getUsername()).thenReturn("alice");
        when(user.getFirstName()).thenReturn("Alice");
        when(user.getLastName()).thenReturn("Ng");
        when(user.getEmail()).thenReturn("alice@acme.test");
        when(user.isEnabled()).thenReturn(true);
        when(user.getFirstAttribute("department")).thenReturn("Engineering");
        when(user.getFirstAttribute("jobTitle")).thenReturn("Engineer");
        return user;
    }

    @Test
    void mapsCoreAttributes() {
        ScimUser scim = new UserMapper(config(null)).toScim(alice());

        assertThat(scim.schemas()).containsExactly(ScimSchemas.USER);
        assertThat(scim.externalId()).isEqualTo("kc-1");
        assertThat(scim.userName()).isEqualTo("alice");
        assertThat(scim.name()).isEqualTo(new ScimName("Alice", "Ng", null));
        assertThat(scim.displayName()).isEqualTo("Alice Ng");
        assertThat(scim.emails()).containsExactly(ScimEmail.work("alice@acme.test"));
        assertThat(scim.active()).isTrue();
        assertThat(scim.enterprise()).isNull();
        assertThat(scim.title()).isNull();
    }

    @Test
    void sendsOnlyExplicitlyMappedCustomAttributes() {
        ScimUser scim = new UserMapper(config(Map.of("enterprise.department", "department", "title", "jobTitle")))
                .toScim(alice());

        assertThat(scim.schemas()).containsExactly(ScimSchemas.USER, ScimSchemas.ENTERPRISE_USER);
        assertThat(scim.enterprise().department()).isEqualTo("Engineering");
        assertThat(scim.title()).isEqualTo("Engineer");
    }

    @Test
    void neverTouchesCredentials() {
        UserModel user = alice();

        new UserMapper(config(Map.of("enterprise.department", "department"))).toScim(user);

        verify(user, Mockito.never()).credentialManager();
        assertThat(ScimJson.write(new UserMapper(config(null)).toScim(user))).doesNotContain("password");
    }

    @Test
    void disabledUserIsInactive() {
        UserModel user = alice();
        when(user.isEnabled()).thenReturn(false);

        assertThat(new UserMapper(config(null)).toScim(user).active()).isFalse();
    }

    @Test
    void displayNameFallsBackToUsername() {
        UserModel user = mock(UserModel.class);
        when(user.getUsername()).thenReturn("svc-bot");
        when(user.getFirstName()).thenReturn(" ");

        assertThat(UserMapper.displayName(user)).isEqualTo("svc-bot");
        when(user.getLastName()).thenReturn("Bot");
        assertThat(UserMapper.displayName(user)).isEqualTo("Bot");
    }

    @Test
    void missingEmailAndNameAreOmitted() {
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("kc-2");
        when(user.getUsername()).thenReturn("bob");
        when(user.isEnabled()).thenReturn(true);

        ScimUser scim = new UserMapper(config(null)).toScim(user);

        assertThat(scim.emails()).isNull();
        assertThat(scim.name()).isNull();
    }

    @Test
    void replacePatchSetsEveryMappedAttribute() {
        UserMapper mapper = new UserMapper(config(Map.of("title", "jobTitle")));
        ScimPatchOp patch = mapper.toReplacePatch(mapper.toScim(alice()));

        assertThat(patch.operations()).extracting(ScimPatchOp.Operation::path)
                .contains("userName", "externalId", "active", "displayName", "name", "emails", "title")
                .doesNotContain("preferredLanguage");
        assertThat(patch.operations()).allMatch(op -> op.op().equals("replace"));
    }

    @Test
    void replacePatchClearsOwnedAttributesThatBecameEmpty() {
        UserModel user = alice();
        when(user.getFirstAttribute("jobTitle")).thenReturn(null);
        UserMapper mapper = new UserMapper(config(Map.of("title", "jobTitle")));

        ScimPatchOp patch = mapper.toReplacePatch(mapper.toScim(user));

        assertThat(patch.operations()).contains(new ScimPatchOp.Operation("remove", "title", null));
    }

    @Test
    void detectsDrift() {
        ScimUser desired = new UserMapper(config(null)).toScim(alice());
        ScimUser same = desired.withId("r1");
        ScimUser drifted = new ScimUser(null, "r1", "kc-1", "alice2", new ScimName("Alice", "Other", null),
                "Alice Other", null, null, null, List.of(ScimEmail.work("old@acme.test")), false, null);

        assertThat(UserMapper.comparableDifferences(desired, same)).isEmpty();
        assertThat(UserMapper.comparableDifferences(desired, drifted))
                .containsExactly("userName", "active", "emails", "displayName", "name");
    }
}
