/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.http;

/**
 * Classification of a SCIM response (report section 5.3).
 */
public enum Outcome {
    /** 2xx. */
    SUCCESS,
    /** 401: refresh credentials once, then retry. */
    UNAUTHORIZED,
    /** 404: the remote object is gone. */
    NOT_FOUND,
    /** 409: the object already exists remotely. */
    CONFLICT,
    /** 412: ETag mismatch; re-read and retry. */
    PRECONDITION_FAILED,
    /** 429, 5xx, timeouts and connection errors: retry later with backoff. */
    RETRY,
    /** Other 4xx (400, 403, ...): retrying cannot help. */
    PERMANENT;

    public static Outcome classify(int status) {
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            case 412 -> PRECONDITION_FAILED;
            case 408, 425, 429 -> RETRY;
            default -> status >= 500 ? RETRY : PERMANENT;
        };
    }
}
