/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ScimResponseTest {

    @Test
    void summarisesScimError() {
        ScimResponse response = new ScimResponse(409,
                "{\"schemas\":[\"urn:ietf:params:scim:api:messages:2.0:Error\"],\"status\":\"409\","
                        + "\"scimType\":\"uniqueness\",\"detail\":\"userName already exists\"}",
                Optional.empty());

        assertThat(response.errorSummary()).isEqualTo("HTTP 409 scimType=uniqueness: userName already exists");
    }

    @Test
    void truncatesAndStripsControlCharacters() {
        String detail = "line1\nline2\u0000" + "x".repeat(1000);
        ScimResponse response = new ScimResponse(400, "{\"detail\":\"" + detail.replace("\n", "\\n")
                .replace("\u0000", "\\u0000") + "\"}", Optional.empty());

        String summary = response.errorSummary();

        assertThat(summary).doesNotContain("\n").doesNotContain("\u0000").endsWith("...");
        assertThat(summary.length()).isLessThan(330);
    }

    @Test
    void neverEchoesNonScimBodies() {
        ScimResponse response = new ScimResponse(500, "<html>stack trace with alice@acme.test</html>", Optional.empty());

        assertThat(response.errorSummary()).isEqualTo("HTTP 500 (non-SCIM error body)");
    }
}
