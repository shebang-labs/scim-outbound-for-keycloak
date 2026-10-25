/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.scim;

/**
 * Schema URNs and media type defined by RFC 7643 and RFC 7644.
 */
public final class ScimSchemas {

    public static final String MEDIA_TYPE = "application/scim+json";

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String ENTERPRISE_USER = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String SERVICE_PROVIDER_CONFIG =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";

    private ScimSchemas() {
    }
}
