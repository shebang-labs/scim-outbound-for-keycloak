/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a {@link TargetConfig} before it is stored. All problems are collected so an admin
 * sees them at once.
 */
public class TargetValidator {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern VAULT_REF = Pattern.compile("^\\$\\{vault\\.[^}]+}$");

    private final HostPolicy hostPolicy;
    private final boolean allowPlaintextSecrets;

    public TargetValidator(HostPolicy hostPolicy, ServerConfig serverConfig) {
        this.hostPolicy = hostPolicy;
        this.allowPlaintextSecrets = serverConfig.allowPlaintextSecrets();
    }

    public static boolean isVaultReference(String value) {
        return value != null && VAULT_REF.matcher(value).matches();
    }

    /**
     * @throws InvalidConfigurationException listing every problem found
     */
    public void validate(TargetConfig config) {
        List<String> errors = new ArrayList<>();
        if (config == null) {
            throw new InvalidConfigurationException("configuration is required");
        }
        if (config.name() == null || !NAME.matcher(config.name()).matches()) {
            errors.add("name must be 1-64 characters of letters, digits, '.', '_' or '-'");
        }
        checkUrl("baseUrl", config.baseUrl(), errors);
        validateAuth(config.auth(), errors);

        int percent = config.maxRemovalPercent();
        if (percent < 0 || percent > 100) {
            errors.add("safety.maxRemovalPercent must be between 0 and 100");
        }
        if (config.removalFloor() < 0) {
            errors.add("safety.removalFloor must not be negative");
        }
        for (Map.Entry<String, String> entry : config.customAttributesOrEmpty().entrySet()) {
            if (!TargetConfig.SUPPORTED_CUSTOM_ATTRIBUTES.contains(entry.getKey())) {
                errors.add("customAttributes: unsupported SCIM attribute '" + entry.getKey() + "'");
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                errors.add("customAttributes: Keycloak attribute for '" + entry.getKey() + "' is empty");
            }
        }
        for (String group : config.scopeGroups()) {
            if (group == null || group.isBlank()) {
                errors.add("scope.groups must not contain empty entries");
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidConfigurationException(String.join("; ", errors));
        }
    }

    private void validateAuth(TargetConfig.Auth auth, List<String> errors) {
        if (auth == null || auth.type() == null) {
            errors.add("auth.type is required (BEARER, OAUTH2_CLIENT_CREDENTIALS or BASIC)");
            return;
        }
        switch (auth.type()) {
            case BEARER -> checkSecret("auth.token", auth.token(), errors);
            case BASIC -> {
                if (blank(auth.username())) {
                    errors.add("auth.username is required for BASIC");
                }
                checkSecret("auth.password", auth.password(), errors);
            }
            case OAUTH2_CLIENT_CREDENTIALS -> {
                checkUrl("auth.tokenUrl", auth.tokenUrl(), errors);
                if (blank(auth.clientId())) {
                    errors.add("auth.clientId is required for OAUTH2_CLIENT_CREDENTIALS");
                }
                checkSecret("auth.clientSecret", auth.clientSecret(), errors);
            }
            default -> errors.add("auth.type is not supported");
        }
    }

    private void checkSecret(String field, String value, List<String> errors) {
        if (blank(value)) {
            errors.add(field + " is required");
        } else if (!allowPlaintextSecrets && !isVaultReference(value)) {
            errors.add(field + " must be a vault reference such as ${vault.my-key}"
                    + " (plain-text secrets are disabled on this server)");
        }
    }

    private void checkUrl(String field, String value, List<String> errors) {
        try {
            hostPolicy.check(value);
        } catch (InvalidConfigurationException e) {
            errors.add(field + ": " + e.getMessage());
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
