# Admin REST API

Base path: `/admin/realms/{realm}/scim-outbound`. Authenticate with an admin access token, as for
Keycloak's own admin API. Reading requires `view-realm`; changing requires `manage-realm`. Every
change is recorded as an admin event (resource type `SCIM_OUTBOUND_TARGET`) with secrets masked.

All bodies are JSON. Errors look like `{"error": "message"}`.

## Targets

| Method and path | Permission | Description |
|-----------------|------------|-------------|
| `GET /targets` | view-realm | List targets (secrets masked). |
| `POST /targets` | manage-realm | Create a target. `201` with the target, `400` on validation errors, `409` if the name exists. |
| `GET /targets/{id}` | view-realm | One target. |
| `PUT /targets/{id}` | manage-realm | Replace the configuration. Masked secrets (`**********`) keep their stored value, unless `baseUrl` (token, password) or `auth.tokenUrl` (client secret) moves to another host: then the secret must be sent again (`400` otherwise). |
| `DELETE /targets/{id}` | manage-realm | Delete the target with its outbox rows and ID map. Remote accounts are not touched. |

A target as returned by the API:

```json
{
  "id": "1f7a...",
  "name": "app-a",
  "enabled": true,
  "baseUrl": "https://app-a.example.com/scim/v2",
  "auth": { "type": "BEARER", "token": "**********" },
  "syncGroups": true,
  "reconcileArmed": false,
  "lastReconcileAt": 1792919146881,
  "lastReconcileSummary": "{...}",
  "createdAt": 1792919000000,
  "updatedAt": 1792919000000
}
```

## Operations

| Method and path | Permission | Description |
|-----------------|------------|-------------|
| `POST /targets/{id}/test` | manage-realm | Calls `/ServiceProviderConfig` with the target's credentials. Returns `{"ok": true, "status": 200, "patchSupported": true, "filterSupported": true}` or `{"ok": false, "message": "..."}`. This is the only request made on the admin's thread; it uses the configured timeouts. |
| `GET /targets/{id}/status` | view-realm | Outbox counts by status, `oldestPendingAgeSeconds`, mapped users and groups, reconciliation state and last report. |
| `GET /targets/{id}/failed?first=0&max=50` | view-realm | Failed rows: id, resource type and id, operation, attempts, sanitised `lastError`. |
| `POST /targets/{id}/failed/{rowId}/retry` | manage-realm | Put one failed row back to `PENDING` (with a fresh retry window). |
| `POST /targets/{id}/failed/retry-all` | manage-realm | Requeue all failed rows: `{"requeued": 12}`. |
| `POST /targets/{id}/reconcile?dryRun=true` | manage-realm | Start a reconciliation in the background (`202`). `dryRun` defaults to `true`. The report appears in `status.lastReconcileSummary`. |
| `POST /worker/run` | manage-realm | Ask the worker to run now instead of at its next tick (`202`). |

### Reconciliation report

```json
{
  "targetId": "1f7a...",
  "dryRun": false,
  "blocked": false,
  "localUsers": 1520,
  "remoteUsers": 1498,
  "userCreates": 22,
  "userUpdates": 3,
  "userRemovals": 1,
  "unmanagedRemoteUsers": 4,
  "groupUpserts": 12,
  "groupRemovals": 0,
  "startedAt": 1792919146830,
  "finishedAt": 1792919149102
}
```

`blocked: true` means the safety brake stopped the run and nothing was queued; `message`
explains why.

## Example session

```bash
KC=https://keycloak.example.com
TOKEN=$(curl -s -d client_id=admin-cli -d username=admin -d password=... -d grant_type=password \
  "$KC/realms/master/protocol/openid-connect/token" | jq -r .access_token)
API="$KC/admin/realms/acme/scim-outbound"

ID=$(curl -s -X POST "$API/targets" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"app-a","baseUrl":"https://app-a.example.com/scim/v2",
       "auth":{"type":"BEARER","token":"${vault.scim-app-a-token}"}}' | jq -r .id)
curl -s -X POST "$API/targets/$ID/test" -H "Authorization: Bearer $TOKEN"
curl -s -X POST "$API/targets/$ID/reconcile?dryRun=true" -H "Authorization: Bearer $TOKEN"
curl -s "$API/targets/$ID/status" -H "Authorization: Bearer $TOKEN" | jq
```
