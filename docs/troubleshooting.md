# Troubleshooting

Enable debug logging for the plugin when investigating (it never logs secrets, tokens or request
bodies, only methods, paths without query strings and status codes):

```bash
kc.sh start --log-level=INFO,io.shebanglabs.scimoutbound:debug
```

## Nothing is sent

| Check | How |
|-------|-----|
| Is the event listener enabled in the realm? | Realm settings → Events → Event listeners must include `scim-outbound`. |
| Is the target enabled? | `GET .../targets/{id}` shows `"enabled": true`. |
| Are rows queued? | `GET .../targets/{id}/status` shows outbox counts. No rows at all means no change reached the plugin. |
| Is the user in scope? | With `scope.groups` set, only members of those groups (or their sub-groups) are sent. |
| Did the worker start? | The startup log contains `scim-outbound: worker every ...`. Background tasks start after database migration. |
| Existing users missing? | Users that existed before the target was created are only sent by a reconciliation: run `reconcile?dryRun=false`. |

## Target creation is rejected

| Message | Meaning |
|---------|---------|
| `URL must use https (plain http is disabled on this server)` | Use HTTPS, or (testing only) start Keycloak with `allow-http=true`. |
| `host ... is not in the server allowlist` | The operator restricted targets with `allowed-hosts`. |
| `host ... resolves to a private or local address` | SSRF protection. Allowlist the host explicitly, or (if intended) set `allow-private-addresses=true`. |
| `... must be a vault reference such as ${vault.my-key}` | Store the secret in the vault, or set `allow-plaintext-secrets=true` (not recommended). |
| `configuration is too large` | The JSON exceeds 4000 characters, usually a very long scope list. |

## Rows fail

| `lastError` | Likely cause and fix |
|-------------|----------------------|
| `HTTP 400 ...` | The application rejects the payload (required attribute missing, unsupported attribute). Check `customAttributes` and the application's SCIM schema. |
| `HTTP 401` / `authentication failed` | Wrong or revoked token; the vault entry is missing (`not available (check the vault entry)`). |
| `HTTP 403` | The SCIM token lacks permissions in the application. |
| `server reported a conflict but no user with this userName was found` | The application enforces uniqueness on another attribute (often email). Clean up the duplicate in the application. |
| `gave up after N attempts` | The application was unavailable longer than `max-retry-hours`. Retry after it recovers. |
| `none of the target's scope groups exist` | A path in `scope.groups` is wrong or the group was renamed. Fixing it is required: the plugin refuses to treat this as "nobody in scope". |
| `TLS verification failed` | Add the application's CA to Keycloak's truststore (`--truststore-paths`). TLS checks cannot be disabled. |

## Duplicates or wrong accounts

The plugin links existing accounts by `userName` before creating. If the application keys users
by email instead, make sure Keycloak usernames match, or create accounts only through the
plugin. Remote accounts the plugin did not create or link are never modified or removed.

## Changes bounce back and forth

A system that pushes users into Keycloak (for example through Keycloak's native SCIM API) and
is also a target can create a loop. Add that system's OAuth client id to the target's
`loopProtection.ignoredClientIds`.
