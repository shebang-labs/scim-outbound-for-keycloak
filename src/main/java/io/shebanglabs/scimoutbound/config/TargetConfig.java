/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
        LoopProtection loopProtection) {

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
     */
    public TargetConfig keepMaskedSecretsFrom(TargetConfig previous) {
        if (auth == null || previous == null || previous.auth() == null) {
            return this;
        }
        Auth old = previous.auth();
        Auth merged = new Auth(auth.type(),
                SECRET_MASK.equals(auth.token()) ? old.token() : auth.token(),
                auth.username(),
                SECRET_MASK.equals(auth.password()) ? old.password() : auth.password(),
                auth.tokenUrl(), auth.clientId(),
                SECRET_MASK.equals(auth.clientSecret()) ? old.clientSecret() : auth.clientSecret(),
                auth.scope());
        return withAuth(merged);
    }

    private TargetConfig withAuth(Auth newAuth) {
        return new TargetConfig(name, enabled, baseUrl, newAuth, deprovision, syncGroups, scope,
                customAttributes, safety, loopProtection);
    }
}
