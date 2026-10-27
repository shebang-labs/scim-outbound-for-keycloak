/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TargetValidatorTest {

    private static TargetValidator validator(Map<String, String> serverOptions) {
        ServerConfig server = ServerConfig.fromLookup(serverOptions::get);
        HostPolicy policy = new HostPolicy(server,
                host -> new InetAddress[] {InetAddress.getByName("203.0.113.5")});
        return new TargetValidator(policy, server);
    }

    private static TargetConfig bearer(String token) {
        return new TargetConfig("app-a", true, "https://app-a.example.com/scim/v2",
                new TargetConfig.Auth(AuthType.BEARER, token, null, null, null, null, null, null),
                null, null, null, null, null, null, null);
    }

    @Test
    void acceptsVaultReferencedBearerToken() {
        assertThatCode(() -> validator(Map.of()).validate(bearer("${vault.scim-app-a-token}")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsVaultKeysOutsideThePrefix() {
        // e.g. the realm's LDAP bind password must not be sendable to a SCIM server
        assertThatThrownBy(() -> validator(Map.of()).validate(bearer("${vault.ldap-bind-password}")))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("must start with 'scim-'");
        assertThatCode(() -> validator(Map.of(ServerConfig.VAULT_KEY_PREFIX, "acme-scim-"))
                .validate(bearer("${vault.acme-scim-a}"))).doesNotThrowAnyException();
        assertThatCode(() -> validator(Map.of(ServerConfig.VAULT_KEY_PREFIX, "*"))
                .validate(bearer("${vault.anything}"))).doesNotThrowAnyException();
    }

    @Test
    void secretsAreCheckedAgainAtTheMomentOfUse() {
        ServerConfig server = ServerConfig.defaults();
        assertThatCode(() -> TargetValidator.requireAllowedVaultKey(server, "${vault.scim-a}"))
                .doesNotThrowAnyException();
        assertThatCode(() -> TargetValidator.requireAllowedVaultKey(server, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TargetValidator.requireAllowedVaultKey(server, "${vault.smtp-password}"))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    @Test
    void rejectsPlaintextSecretUnlessOperatorAllowsIt() {
        assertThatThrownBy(() -> validator(Map.of()).validate(bearer("raw-token")))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("vault reference")
                .hasMessageNotContaining("raw-token");

        assertThatCode(() -> validator(Map.of(ServerConfig.ALLOW_PLAINTEXT_SECRETS, "true"))
                .validate(bearer("raw-token"))).doesNotThrowAnyException();
    }

    @Test
    void oauthRequiresTokenUrlClientIdAndSecret() {
        TargetConfig config = new TargetConfig("app-b", true, "https://app-b.example.com/scim",
                new TargetConfig.Auth(AuthType.OAUTH2_CLIENT_CREDENTIALS, null, null, null, null, null, null, null),
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> validator(Map.of()).validate(config))
                .hasMessageContaining("auth.tokenUrl")
                .hasMessageContaining("auth.clientId")
                .hasMessageContaining("auth.clientSecret");
    }

    @Test
    void basicRequiresUsername() {
        TargetConfig config = new TargetConfig("app-c", true, "https://app-c.example.com/scim",
                new TargetConfig.Auth(AuthType.BASIC, null, null, "${vault.scim-pw}", null, null, null, null),
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> validator(Map.of()).validate(config)).hasMessageContaining("auth.username");
    }

    @Test
    void reportsAllProblemsTogether() {
        TargetConfig config = new TargetConfig("bad name!", true, "http://x.example.com",
                null, null, null, new TargetConfig.Scope(List.of(" ")),
                Map.of("password", "pw"), new TargetConfig.Safety(150, -1, null), null, null);

        assertThatThrownBy(() -> validator(Map.of()).validate(config))
                .hasMessageContaining("name must be")
                .hasMessageContaining("baseUrl")
                .hasMessageContaining("auth.type")
                .hasMessageContaining("unsupported SCIM attribute 'password'")
                .hasMessageContaining("maxRemovalPercent")
                .hasMessageContaining("removalFloor")
                .hasMessageContaining("scope.groups");
    }

    @Test
    void vaultReferencePattern() {
        assertThat(TargetValidator.isVaultReference("${vault.k}")).isTrue();
        assertThat(TargetValidator.isVaultReference("${vault.}")).isFalse();
        assertThat(TargetValidator.isVaultReference("x${vault.k}")).isFalse();
        assertThat(TargetValidator.isVaultReference(null)).isFalse();
    }
}
