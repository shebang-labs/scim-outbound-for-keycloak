/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Realm-admin-editable configuration of one SCIM target. This is the JSON shape accepted by the
 * admin REST API and stored in the database. Secret fields hold {@code ${vault.key}} references
 * (or, when the operator allows it, literal values) and are always masked when read back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TargetConfig(
        String name,
        Boolean enabled,
        String baseUrl,
        Auth auth,
        DeprovisionMode deprovision,
        Boolean syncGroups,
        Scope scope,
        Map<String, String> customAttributes,
        Safety safety,
        LoopProtection loopProtection,
        Boolean linkExistingAccounts) {

    /** Value returned in place of every secret. Sending it back on update keeps the stored secret. */
    public static final String SECRET_MASK = "**********";

    /**
     * SCIM attributes that can be filled from a Keycloak user attribute via
     * {@link #customAttributes} (key: SCIM attribute, value: Keycloak attribute name).
     */
    public static final Set<String> SUPPORTED_CUSTOM_ATTRIBUTES = Set.of(
            "title", "preferredLanguage", "locale",
            "enterprise.employeeNumber", "enterprise.costCenter", "enterprise.organization",
            "enterprise.division", "enterprise.department");

    /** Authentication settings. Which fields are required depends on {@link #type}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Auth(
            AuthType type,
            String token,
            String username,
            String password,
            String tokenUrl,
            String clientId,
            String clientSecret,
            String scope) {

        Auth mapSecrets(UnaryOperator<String> fn) {
            return new Auth(type, fn.apply(token), username, fn.apply(password), tokenUrl, clientId,
                    fn.apply(clientSecret), scope);
        }
    }

    /**
     * Limits which users are provisioned. When {@code groups} is set, only members (direct or
     * through sub-groups) of those groups are sent, and with group sync on, only those groups and
     * their sub-groups.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scope(List<String> groups) {
        @JsonIgnore
        public boolean isRestricted() {
            return groups != null && !groups.isEmpty();
        }
    }

    /** Brakes against mass removal. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Safety(Integer maxRemovalPercent, Integer removalFloor, Boolean firstReconcileDryRun) {
    }

    /** Prevents provisioning loops with systems that also push into Keycloak. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoopProtection(List<String> ignoredClientIds) {
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    /**
     * Whether an account that already exists on the target with the same {@code userName} (or a
     * group with the same {@code displayName}) may be taken over although its {@code externalId}
     * does not name the Keycloak user or group. Off by default: taking over an account means
     * overwriting it now and deactivating or deleting it when the Keycloak user goes away.
     */
    public boolean linkExistingAccountsOrDefault() {
        return linkExistingAccounts != null && linkExistingAccounts;
    }

    public boolean syncGroupsOrDefault() {
        return syncGroups != null && syncGroups;
    }

    public DeprovisionMode deprovisionOrDefault() {
        return deprovision == null ? DeprovisionMode.DEACTIVATE : deprovision;
    }

    public int maxRemovalPercent() {
        return safety == null || safety.maxRemovalPercent() == null ? 5 : safety.maxRemovalPercent();
    }

    public int removalFloor() {
        return safety == null || safety.removalFloor() == null ? 10 : safety.removalFloor();
    }

    public boolean firstReconcileDryRun() {
        return safety == null || safety.firstReconcileDryRun() == null || safety.firstReconcileDryRun();
    }

    public List<String> scopeGroups() {
        return scope == null || scope.groups() == null ? List.of() : scope.groups();
    }

    public List<String> ignoredClientIds() {
        return loopProtection == null || loopProtection.ignoredClientIds() == null
                ? List.of() : loopProtection.ignoredClientIds();
    }

    public Map<String, String> customAttributesOrEmpty() {
        return customAttributes == null ? Map.of() : customAttributes;
    }

    /** Copy with every secret replaced by {@link #SECRET_MASK} (null secrets stay null). */
    public TargetConfig masked() {
        return withAuth(auth == null ? null : auth.mapSecrets(s -> s == null ? null : SECRET_MASK));
    }

    /**
     * Copy where secrets sent back as {@link #SECRET_MASK} are replaced by the stored values of
     * {@code previous}, so admins can edit a target without re-entering its secrets.
     *
     * <p>A masked secret is only kept while it still goes where it went before: the token or
     * password to the same {@code baseUrl} host, the client secret to the same {@code tokenUrl}
     * host. Otherwise an admin who cannot see a secret could send it to a server of their choice
     * by changing the URL alone, so it has to be entered again.
     *
     * @throws InvalidConfigurationException when a masked secret would go to another host
     */
    public TargetConfig keepMaskedSecretsFrom(TargetConfig previous) {
        if (auth == null || previous == null || previous.auth() == null) {
            return this;
        }
        Auth old = previous.auth();
        boolean sameTarget = sameOrigin(baseUrl, previous.baseUrl());
        if (!sameTarget && (SECRET_MASK.equals(auth.token()) || SECRET_MASK.equals(auth.password()))) {
            throw new InvalidConfigurationException("baseUrl points to another host: enter auth.token or"
                    + " auth.password again instead of the masked value");
        }
        if (!sameOrigin(auth.tokenUrl(), old.tokenUrl()) && SECRET_MASK.equals(auth.clientSecret())) {
            throw new InvalidConfigurationException("auth.tokenUrl points to another host: enter"
                    + " auth.clientSecret again instead of the masked value");
        }
        Auth merged = new Auth(auth.type(),
                SECRET_MASK.equals(auth.token()) ? old.token() : auth.token(),
                auth.username(),
                SECRET_MASK.equals(auth.password()) ? old.password() : auth.password(),
                auth.tokenUrl(), auth.clientId(),
                SECRET_MASK.equals(auth.clientSecret()) ? old.clientSecret() : auth.clientSecret(),
                auth.scope());
        return withAuth(merged);
    }

    /** Same scheme, host and port; unparsable or missing URLs never match. */
    static boolean sameOrigin(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            URI x = new URI(a.trim());
            URI y = new URI(b.trim());
            return x.getScheme() != null && x.getHost() != null
                    && x.getScheme().equalsIgnoreCase(y.getScheme())
                    && x.getHost().equalsIgnoreCase(y.getHost())
                    && port(x) == port(y);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443;
    }

    private TargetConfig withAuth(Auth newAuth) {
        return new TargetConfig(name, enabled, baseUrl, newAuth, deprovision, syncGroups, scope,
                customAttributes, safety, loopProtection, linkExistingAccounts);
    }
}
