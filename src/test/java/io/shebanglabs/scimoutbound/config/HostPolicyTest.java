/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostPolicyTest {

    private static final Map<String, String> DNS = Map.of(
            "app.example.com", "203.0.113.10",
            "internal.example.com", "10.1.2.3",
            "metadata.example.com", "169.254.169.254",
            "localhost", "127.0.0.1",
            "v6.example.com", "fd00::1");

    private static InetAddress[] fakeResolve(String host) throws UnknownHostException {
        String ip = DNS.get(host);
        if (ip == null) {
            if (Character.isDigit(host.charAt(0)) || host.contains(":")) {
                return new InetAddress[] {InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(ip)};
    }

    private static HostPolicy policy(Map<String, String> options) {
        return new HostPolicy(ServerConfig.fromLookup(options::get), HostPolicyTest::fakeResolve);
    }

    @Test
    void acceptsPublicHttpsHost() {
        assertThat(policy(Map.of()).check("https://app.example.com/scim/v2").getHost())
                .isEqualTo("app.example.com");
    }

    @Test
    void rejectsHttpByDefault() {
        assertThatThrownBy(() -> policy(Map.of()).check("http://app.example.com/scim"))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("https");
    }

    @Test
    void acceptsHttpWhenOperatorAllowsIt() {
        assertThat(policy(Map.of(ServerConfig.ALLOW_HTTP, "true")).check("http://app.example.com/scim"))
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://internal.example.com/scim",
        "https://metadata.example.com/latest",
        "https://localhost/scim",
        "https://127.0.0.1/scim",
        "https://[::1]/scim",
        "https://v6.example.com/scim",
        "https://100.64.0.1/scim"})
    void rejectsNonPublicAddresses(String url) {
        assertThatThrownBy(() -> policy(Map.of()).check(url))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void privateAddressesCanBeAllowedServerWide() {
        assertThat(policy(Map.of(ServerConfig.ALLOW_PRIVATE_ADDRESSES, "true"))
                .check("https://internal.example.com/scim")).isNotNull();
    }

    @Test
    void allowlistIsEnforcedAndOverridesPrivateCheck() {
        HostPolicy policy = policy(Map.of(ServerConfig.ALLOWED_HOSTS, "*.example.com"));

        assertThat(policy.check("https://internal.example.com/scim")).isNotNull();
        assertThatThrownBy(() -> policy.check("https://evil.test/scim"))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void wildcardDoesNotMatchTheBareDomain() {
        HostPolicy policy = policy(Map.of(ServerConfig.ALLOWED_HOSTS, "*.example.com"));

        assertThat(policy.matchesAllowlist("example.com")).isFalse();
        assertThat(policy.matchesAllowlist("a.example.com")).isTrue();
        assertThat(policy.matchesAllowlist("aexample.com")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://user:pw@app.example.com/scim",
        "https://app.example.com/scim?x=1",
        "https://app.example.com/scim#frag",
        "ftp://app.example.com/",
        "not a url",
        ""})
    void rejectsMalformedOrUnsafeUrls(String url) {
        assertThatThrownBy(() -> policy(Map.of()).check(url))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    @Test
    void unresolvableHostIsRejected() {
        assertThatThrownBy(() -> policy(Map.of()).check("https://nope.invalid/scim"))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("cannot be resolved");
    }
}
