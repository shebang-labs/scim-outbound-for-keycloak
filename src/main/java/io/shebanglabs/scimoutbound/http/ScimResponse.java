/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

import io.shebanglabs.scimoutbound.scim.ScimError;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * A fully read SCIM response.
 *
 * @param status     HTTP status code
 * @param body       response body (bounded in size), possibly empty
 * @param retryAfter value of the {@code Retry-After} header, if present and parseable
 */
public record ScimResponse(int status, String body, Optional<Duration> retryAfter) {

    private static final int MAX_DETAIL = 300;

    public Outcome outcome() {
        return Outcome.classify(status);
    }

    public boolean isSuccess() {
        return outcome() == Outcome.SUCCESS;
    }

    public <T> T as(Class<T> type) throws IOException {
        return ScimJson.read(body, type);
    }

    /**
     * Short, sanitised description of an error for storage and display. Only the SCIM
     * {@code scimType} and a truncated {@code detail} are kept; control characters are stripped.
     * Servers can echo personal data in error details, so the text is also truncated hard.
     */
    public String errorSummary() {
        StringBuilder sb = new StringBuilder("HTTP ").append(status);
        if (body != null && !body.isBlank()) {
            try {
                ScimError error = ScimJson.read(body, ScimError.class);
                if (error.scimType() != null) {
                    sb.append(" scimType=").append(sanitize(error.scimType(), 40));
                }
                if (error.detail() != null) {
                    sb.append(": ").append(sanitize(error.detail(), MAX_DETAIL));
                }
            } catch (IOException | RuntimeException e) {
                sb.append(" (non-SCIM error body)");
            }
        }
        return sb.toString();
    }

    static String sanitize(String text, int max) {
        String cleaned = text.replaceAll("[\\p{Cntrl}]", " ").strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "...";
    }
}
