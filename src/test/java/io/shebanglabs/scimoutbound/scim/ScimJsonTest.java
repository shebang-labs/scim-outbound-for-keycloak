/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScimJsonTest {

    @Test
    void serialisesUserWithEnterpriseExtensionAndNoNulls() {
        ScimUser user = new ScimUser(List.of(ScimSchemas.USER, ScimSchemas.ENTERPRISE_USER), null,
                "kc-1", "alice", ScimName.of("Alice", "Ng"), "Alice Ng", null, null, null,
                List.of(ScimEmail.work("alice@acme.test")), true,
                new ScimEnterpriseUser(null, null, null, null, "Engineering"));

        String json = ScimJson.write(user);

        assertThat(json)
                .contains("\"userName\":\"alice\"")
                .contains("\"externalId\":\"kc-1\"")
                .contains("\"" + ScimSchemas.ENTERPRISE_USER + "\":{\"department\":\"Engineering\"}")
                .contains("\"emails\":[{\"value\":\"alice@acme.test\",\"type\":\"work\",\"primary\":true}]")
                .doesNotContain("null")
                .doesNotContain("\"id\"")
                .doesNotContain("password");
    }

    @Test
    void parsesListResponseAndIgnoresUnknownAttributes() throws IOException {
        String json = """
                {"schemas":["urn:ietf:params:scim:api:messages:2.0:ListResponse"],
                 "totalResults":1,"itemsPerPage":1,"startIndex":1,
                 "Resources":[{"id":"r-1","userName":"alice","active":true,
                   "meta":{"resourceType":"User"},"x-vendor":{"a":1}}]}
                """;

        ScimListResponse<ScimUser> list = ScimJson.read(json, new TypeReference<>() { });

        assertThat(list.total()).isEqualTo(1);
        assertThat(list.resourcesOrEmpty()).singleElement()
                .satisfies(u -> {
                    assertThat(u.id()).isEqualTo("r-1");
                    assertThat(u.active()).isTrue();
                });
    }

    @Test
    void parsesErrorWithNumericStatus() throws IOException {
        ScimError error = ScimJson.read(
                "{\"schemas\":[\"" + ScimSchemas.ERROR + "\"],\"status\":409,\"scimType\":\"uniqueness\"}",
                ScimError.class);

        assertThat(error.status()).isEqualTo("409");
        assertThat(error.scimType()).isEqualTo("uniqueness");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> ScimJson.read("{\"id\":\"a\",\"id\":\"b\"}", ScimUser.class))
                .isInstanceOf(IOException.class);
    }

    @Test
    void patchOpUsesCapitalisedOperationsKey() {
        String json = ScimJson.write(ScimPatchOp.builder().replace("active", false).build());

        assertThat(json).isEqualTo("{\"schemas\":[\"" + ScimSchemas.PATCH_OP + "\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"active\",\"value\":false}]}");
    }

    @Test
    void memberRefIsSerialisedWithDollarSign() {
        assertThat(ScimJson.write(new ScimMember("u-1", null, "https://x/Users/u-1", null)))
                .isEqualTo("{\"value\":\"u-1\",\"$ref\":\"https://x/Users/u-1\"}");
    }

    @Test
    void serviceProviderConfigDefaultsToPatchWhenMissing() throws IOException {
        ScimServiceProviderConfig none = ScimJson.read("{}", ScimServiceProviderConfig.class);
        ScimServiceProviderConfig noPatch =
                ScimJson.read("{\"patch\":{\"supported\":false}}", ScimServiceProviderConfig.class);

        assertThat(none.patchSupported()).isTrue();
        assertThat(noPatch.patchSupported()).isFalse();
    }

    @Test
    void primaryEmailPrefersPrimaryFlag() {
        ScimUser user = new ScimUser(null, null, null, "a", null, null, null, null, null,
                List.of(new ScimEmail("home@x", "home", false), new ScimEmail("work@x", "work", true)),
                null, null);

        assertThat(user.primaryEmail()).isEqualTo("work@x");
    }
}
