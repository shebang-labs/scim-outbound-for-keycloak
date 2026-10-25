/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SCIM User resource (RFC 7643 section 4.1). Only attributes this plugin can send are modelled;
 * unknown attributes in server responses are ignored. Passwords are deliberately absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUser(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        ScimName name,
        String displayName,
        String title,
        String preferredLanguage,
        String locale,
        List<ScimEmail> emails,
        Boolean active,
        @JsonProperty(ScimSchemas.ENTERPRISE_USER) ScimEnterpriseUser enterprise) {

    /** Returns the primary email (or the first one), or {@code null}. */
    public String primaryEmail() {
        if (emails == null || emails.isEmpty()) {
            return null;
        }
        return emails.stream()
                .filter(e -> Boolean.TRUE.equals(e.primary()))
                .findFirst()
                .orElse(emails.get(0))
                .value();
    }

    public ScimUser withId(String newId) {
        return new ScimUser(schemas, newId, externalId, userName, name, displayName, title,
                preferredLanguage, locale, emails, active, enterprise);
    }
}
