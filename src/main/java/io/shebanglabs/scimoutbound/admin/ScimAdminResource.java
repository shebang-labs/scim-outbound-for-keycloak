/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 Shebang Labs
 */
package io.shebanglabs.scimoutbound.admin;

import io.shebanglabs.scimoutbound.auth.SecretResolver;
import io.shebanglabs.scimoutbound.config.InvalidConfigurationException;
import io.shebanglabs.scimoutbound.config.ScimTarget;
import io.shebanglabs.scimoutbound.config.TargetConfig;
import io.shebanglabs.scimoutbound.http.ScimClient;
import io.shebanglabs.scimoutbound.http.ScimException;
import io.shebanglabs.scimoutbound.http.ScimResponse;
import io.shebanglabs.scimoutbound.jpa.OutboxEntity;
import io.shebanglabs.scimoutbound.outbox.OutboxStatus;
import io.shebanglabs.scimoutbound.outbox.ResourceType;
import io.shebanglabs.scimoutbound.scim.ScimJson;
import io.shebanglabs.scimoutbound.scim.ScimServiceProviderConfig;
import io.shebanglabs.scimoutbound.spi.DefaultScimOutboundProviderFactory;
import io.shebanglabs.scimoutbound.spi.ScimOutboundProvider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resources.admin.AdminEventBuilder;
import org.keycloak.services.resources.admin.fgap.AdminPermissionEvaluator;

/**
 * Admin REST API, mounted at {@code /admin/realms/{realm}/scim-outbound}. Reading requires
 * {@code view-realm}; every change requires {@code manage-realm} and is recorded as an admin
 * event (with secrets masked). Secrets are never returned.
 */
public class ScimAdminResource {

    static final String AUDIT_RESOURCE = "SCIM_OUTBOUND_TARGET";
    private static final int MAX_PAGE = 200;

    private final KeycloakSession session;
    private final RealmModel realm;
    private final AdminPermissionEvaluator auth;
    private final AdminEventBuilder adminEvent;
    private final DefaultScimOutboundProviderFactory factory;
    private final ScimOutboundProvider provider;

    public ScimAdminResource(KeycloakSession session, RealmModel realm, AdminPermissionEvaluator auth,
            AdminEventBuilder adminEvent, DefaultScimOutboundProviderFactory factory) {
        this.session = session;
        this.realm = realm;
        this.auth = auth;
        this.adminEvent = adminEvent == null ? null : adminEvent.resource(AUDIT_RESOURCE);
        this.factory = factory;
        this.provider = session.getProvider(ScimOutboundProvider.class);
    }

    // ---- targets -----------------------------------------------------------------------------

    @GET
    @Path("targets")
    @Produces(MediaType.APPLICATION_JSON)
    public Response list() {
        auth.realm().requireViewRealm();
        return json(Response.Status.OK, provider.targets().listByRealm(realm.getId()).stream()
                .map(TargetView::of).toList());
    }

    @GET
    @Path("targets/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("id") String id) {
        auth.realm().requireViewRealm();
        return provider.targets().get(realm.getId(), id)
                .map(t -> json(Response.Status.OK, TargetView.of(t)))
                .orElseGet(ScimAdminResource::notFound);
    }

    @POST
    @Path("targets")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(String body) {
        auth.realm().requireManageRealm();
        try {
            TargetConfig config = parse(body);
            factory.validator().validate(config);
            if (nameTaken(config.name(), null)) {
                return error(Response.Status.CONFLICT, "a target named '" + config.name() + "' already exists");
            }
            ScimTarget created = provider.targets().create(realm.getId(), config, now());
            audit(OperationType.CREATE, created);
            return json(Response.Status.CREATED, TargetView.of(created));
        } catch (InvalidConfigurationException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    @PUT
    @Path("targets/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("id") String id, String body) {
        auth.realm().requireManageRealm();
        var existing = provider.targets().get(realm.getId(), id);
        if (existing.isEmpty()) {
            return notFound();
        }
        try {
            TargetConfig config = parse(body).keepMaskedSecretsFrom(existing.get().config());
            factory.validator().validate(config);
            if (nameTaken(config.name(), id)) {
                return error(Response.Status.CONFLICT, "a target named '" + config.name() + "' already exists");
            }
            ScimTarget updated = provider.targets().update(realm.getId(), id, config, now()).orElseThrow();
            factory.targetChanged(id);
            audit(OperationType.UPDATE, updated);
            return json(Response.Status.OK, TargetView.of(updated));
        } catch (InvalidConfigurationException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    @DELETE
    @Path("targets/{id}")
    public Response delete(@PathParam("id") String id) {
        auth.realm().requireManageRealm();
        var existing = provider.targets().get(realm.getId(), id);
        if (existing.isEmpty()) {
            return notFound();
        }
        provider.outbox().deleteByTarget(id);
        provider.mappings().deleteByTarget(id);
        provider.targets().delete(realm.getId(), id);
        factory.targetChanged(id);
        audit(OperationType.DELETE, existing.get());
        return Response.noContent().build();
    }

    // ---- operations --------------------------------------------------------------------------

    /**
     * Calls {@code /ServiceProviderConfig} with the target's credentials. This is the only request
     * the plugin makes on an admin's request thread, and it uses the configured timeouts.
     */
    @POST
    @Path("targets/{id}/test")
    @Produces(MediaType.APPLICATION_JSON)
    public Response test(@PathParam("id") String id) {
        auth.realm().requireManageRealm();
        var target = provider.targets().get(realm.getId(), id);
        if (target.isEmpty()) {
            return notFound();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            session.getContext().setRealm(realm);
            ScimClient client = factory.clientFactory().create(target.get(), SecretResolver.vault(session));
            ScimResponse response = client.probe();
            result.put("ok", response.isSuccess());
            result.put("status", response.status());
            if (response.isSuccess()) {
                ScimServiceProviderConfig spc = response.as(ScimServiceProviderConfig.class);
                result.put("patchSupported", spc.patchSupported());
                result.put("filterSupported", spc.filterSupported());
            } else {
                result.put("message", response.errorSummary());
            }
        } catch (InvalidConfigurationException e) {
            result.put("ok", false);
            result.put("message", "configuration rejected: " + e.getMessage());
        } catch (ScimException e) {
            result.put("ok", false);
            result.put("message", e.getMessage());
        } catch (IOException e) {
            result.put("ok", true);
            result.put("message", "connected, but ServiceProviderConfig is not valid JSON");
        }
        return json(Response.Status.OK, result);
    }

    @GET
    @Path("targets/{id}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String id) {
        auth.realm().requireViewRealm();
        var target = provider.targets().get(realm.getId(), id);
        if (target.isEmpty()) {
            return notFound();
        }
        Map<OutboxStatus, Long> counts = provider.outbox().countByStatus(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetId", id);
        result.put("enabled", target.get().enabled());
        result.put("outbox", counts);
        result.put("oldestPendingAgeSeconds", provider.outbox().oldestOpenCreatedAt(id)
                .map(t -> Math.max(0, (now() - t) / 1000)).orElse(0L));
        result.put("mappedUsers", provider.mappings().count(id, ResourceType.USER));
        result.put("mappedGroups", provider.mappings().count(id, ResourceType.GROUP));
        result.put("reconcileArmed", target.get().reconcileArmed());
        result.put("lastReconcileAt", target.get().lastReconcileAt());
        result.put("lastReconcileSummary", target.get().lastReconcileSummary());
        return json(Response.Status.OK, result);
    }

    @GET
    @Path("targets/{id}/failed")
    @Produces(MediaType.APPLICATION_JSON)
    public Response failed(@PathParam("id") String id, @QueryParam("first") @DefaultValue("0") int first,
            @QueryParam("max") @DefaultValue("50") int max) {
        auth.realm().requireViewRealm();
        if (provider.targets().get(realm.getId(), id).isEmpty()) {
            return notFound();
        }
        List<Map<String, Object>> rows = provider.outbox()
                .failed(id, Math.max(0, first), Math.min(Math.max(1, max), MAX_PAGE)).stream()
                .map(ScimAdminResource::failedRow).toList();
        return json(Response.Status.OK, rows);
    }

    @POST
    @Path("targets/{id}/failed/{rowId}/retry")
    public Response retry(@PathParam("id") String id, @PathParam("rowId") String rowId) {
        auth.realm().requireManageRealm();
        if (provider.targets().get(realm.getId(), id).isEmpty()) {
            return notFound();
        }
        long now = now();
        boolean ok = provider.outbox().retry(id, rowId, now, now + factory.serverConfig().maxRetryAge().toMillis());
        if (!ok) {
            return error(Response.Status.NOT_FOUND, "no failed row with this id");
        }
        audit(OperationType.ACTION, null);
        return Response.noContent().build();
    }

    @POST
    @Path("targets/{id}/failed/retry-all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response retryAll(@PathParam("id") String id) {
        auth.realm().requireManageRealm();
        if (provider.targets().get(realm.getId(), id).isEmpty()) {
            return notFound();
        }
        long now = now();
        int count = provider.outbox().retryAllFailed(id, now, now + factory.serverConfig().maxRetryAge().toMillis());
        audit(OperationType.ACTION, null);
        return json(Response.Status.OK, Map.of("requeued", count));
    }

    /**
     * Starts a reconciliation in the background (dry run by default). The report appears in the
     * target's status as {@code lastReconcileSummary}. A successful non-dry run arms scheduled
     * reconciliation for this target.
     */
    @POST
    @Path("targets/{id}/reconcile")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reconcile(@PathParam("id") String id, @QueryParam("dryRun") @DefaultValue("true") boolean dryRun) {
        auth.realm().requireManageRealm();
        if (provider.targets().get(realm.getId(), id).isEmpty()) {
            return notFound();
        }
        if (factory.triggerReconcile(id, dryRun).isEmpty()) {
            return error(Response.Status.CONFLICT, "a reconciliation of this target is already queued or running");
        }
        audit(OperationType.ACTION, null);
        return Response.accepted().type(MediaType.APPLICATION_JSON)
                .entity(ScimJson.write(Map.of("accepted", true, "dryRun", dryRun))).build();
    }

    /** Asks the worker to run now instead of waiting for its next tick (useful after a fix). */
    @POST
    @Path("worker/run")
    @Produces(MediaType.APPLICATION_JSON)
    public Response runWorker() {
        auth.realm().requireManageRealm();
        factory.triggerDrain();
        // Keycloak's security headers filter rejects a non-204 response without a media type
        return json(Response.Status.ACCEPTED, Map.of("accepted", true));
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static Map<String, Object> failedRow(OutboxEntity e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("resourceType", e.getResourceType());
        row.put("resourceId", e.getResourceId());
        if (e.getRelatedId() != null) {
            row.put("relatedId", e.getRelatedId());
        }
        row.put("operation", e.getOperation());
        row.put("attempts", e.getAttempts());
        row.put("lastError", e.getLastError());
        row.put("createdAt", e.getCreatedAt());
        row.put("updatedAt", e.getUpdatedAt());
        return row;
    }

    private boolean nameTaken(String name, String exceptId) {
        return provider.targets().listByRealm(realm.getId()).stream()
                .anyMatch(t -> t.name().equals(name) && !t.id().equals(exceptId));
    }

    private void audit(OperationType operation, ScimTarget target) {
        if (adminEvent == null) {
            return;
        }
        AdminEventBuilder event = adminEvent.operation(operation).resourcePath(session.getContext().getUri());
        if (target != null) {
            event.representation(TargetView.of(target));
        }
        event.success();
    }

    private static TargetConfig parse(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidConfigurationException("request body is required");
        }
        try {
            return ScimJson.read(body, TargetConfig.class);
        } catch (IOException e) {
            throw new InvalidConfigurationException("request body is not a valid target configuration");
        }
    }

    private long now() {
        return factory.clock().millis();
    }

    private static Response json(Response.Status status, Object entity) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(ScimJson.write(entity)).build();
    }

    private static Response error(Response.Status status, String message) {
        return json(status, Map.of("error", message));
    }

    private static Response notFound() {
        return error(Response.Status.NOT_FOUND, "target not found");
    }
}
