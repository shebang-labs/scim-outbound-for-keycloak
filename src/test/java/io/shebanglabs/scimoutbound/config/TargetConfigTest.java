/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.shebanglabs.scimoutbound.scim.ScimJson;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TargetConfigTest {

    private static final TargetConfig OAUTH = new TargetConfig("app", null, "https://a.example.com/scim",
            new TargetConfig.Auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null,
                    "https://a.example.com/token", "kc", "${vault.secret}", "scim.write"),
            null, null, null, null, null, null, null);

    @Test
    void defaultsMatchSecureDesign() {
        assertThat(OAUTH.enabledOrDefault()).isTrue();
        assertThat(OAUTH.syncGroupsOrDefault()).isFalse();
        assertThat(OAUTH.deprovisionOrDefault()).isEqualTo(DeprovisionMode.DEACTIVATE);
        assertThat(OAUTH.maxRemovalPercent()).isEqualTo(5);
        assertThat(OAUTH.firstReconcileDryRun()).isTrue();
        assertThat(OAUTH.scopeGroups()).isEmpty();
    }

    @Test
    void maskingHidesEverySecretButKeepsOtherFields() {
        TargetConfig masked = OAUTH.masked();

        assertThat(masked.auth().clientSecret()).isEqualTo(TargetConfig.SECRET_MASK);
        assertThat(masked.auth().token()).isNull();
        assertThat(masked.auth().clientId()).isEqualTo("kc");
        assertThat(ScimJson.write(masked)).doesNotContain("vault.secret");
    }

    @Test
    void maskedSecretsAreRestoredFromPreviousConfig() {
        TargetConfig edited = OAUTH.masked();

        assertThat(edited.keepMaskedSecretsFrom(OAUTH).auth().clientSecret()).isEqualTo("${vault.secret}");
    }

    @Test
    void maskedSecretIsNotSentToANewHost() {
        // otherwise an admin could redirect a secret they cannot see by editing the URL only
        TargetConfig.Auth a = OAUTH.masked().auth();
        TargetConfig movedBase = new TargetConfig("app", null, "https://evil.example.net/scim",
                new TargetConfig.Auth(AuthType.BEARER, TargetConfig.SECRET_MASK, null, null, null, null, null, null),
                null, null, null, null, null, null, null);
        TargetConfig movedTokenUrl = new TargetConfig("app", null, OAUTH.baseUrl(),
                new TargetConfig.Auth(a.type(), null, null, null, "https://evil.example.net/token", a.clientId(),
                        a.clientSecret(), null),
                null, null, null, null, null, null, null);
        TargetConfig bearer = new TargetConfig("app", null, OAUTH.baseUrl(),
                new TargetConfig.Auth(AuthType.BEARER, "${vault.scim-old}", null, null, null, null, null, null),
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> movedBase.keepMaskedSecretsFrom(bearer))
                .isInstanceOf(InvalidConfigurationException.class).hasMessageContaining("auth.token");
        assertThatThrownBy(() -> movedTokenUrl.keepMaskedSecretsFrom(OAUTH))
                .isInstanceOf(InvalidConfigurationException.class).hasMessageContaining("auth.clientSecret");
    }

    @Test
    void maskedSecretIsKeptWhenOnlyThePathChanges() {
        TargetConfig bearer = new TargetConfig("app", null, "https://a.example.com/scim",
                new TargetConfig.Auth(AuthType.BEARER, "${vault.scim-old}", null, null, null, null, null, null),
                null, null, null, null, null, null, null);
        TargetConfig edited = new TargetConfig("app", null, "https://A.example.com:443/scim/v2",
                new TargetConfig.Auth(AuthType.BEARER, TargetConfig.SECRET_MASK, null, null, null, null, null, null),
                null, null, null, null, null, null, null);

        assertThat(edited.keepMaskedSecretsFrom(bearer).auth().token()).isEqualTo("${vault.scim-old}");
    }

    @Test
    void newSecretReplacesOldOne() {
        TargetConfig.Auth a = OAUTH.auth();
        TargetConfig edited = new TargetConfig("app", null, OAUTH.baseUrl(),
                new TargetConfig.Auth(a.type(), null, null, null, a.tokenUrl(), a.clientId(), "${vault.new}", null),
                null, null, null, null, null, null, null);

        assertThat(edited.keepMaskedSecretsFrom(OAUTH).auth().clientSecret()).isEqualTo("${vault.new}");
    }

    @Test
    void roundTripsThroughJson() throws IOException {
        TargetConfig parsed = ScimJson.read(ScimJson.write(OAUTH), TargetConfig.class);

        assertThat(parsed).isEqualTo(OAUTH);
    }
}
