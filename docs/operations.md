# Operating the plugin

## Installing and upgrading

1. Check the [compatibility table](../README.md#compatibility) for your Keycloak version.
2. Verify the release: `sha256sum -c scim-outbound-for-keycloak-<version>.jar.sha256` and, if
   you use it, the GPG signature or GitHub build provenance attestation.
3. Copy the jar into `providers/`, remove any older version of it, and run `kc.sh build`.
4. Start Keycloak. The plugin's Liquibase changelog creates or upgrades its tables on first start.
5. Watch the logs for `scim-outbound: worker every ...` and for errors during the first hour.

Downgrades are not supported once a newer version has migrated the tables.

### Keycloak on a private network

- **Egress proxy.** The plugin sends its requests through Keycloak's shared HTTP client, so
  Keycloak's own proxy setting applies to it: for example
  `--spi-connections-http-client--default--proxy-mappings='.*;http://proxy.internal:3128'`. Requests
  to HTTPS targets go through the proxy as a `CONNECT` tunnel; TLS stays end to end, and the
  target's certificate is checked by Keycloak, not by the proxy.
- **List the targets in `allowed-hosts`.** A host on that list is not resolved by the plugin's
  address check, which matters when the Keycloak host cannot resolve public names itself (all DNS
  happens at the proxy) and lets internal applications on private addresses be targets without
  `allow-private-addresses`.
- **Internal certificate authorities.** Add them to Keycloak's truststore (`truststore-paths`).

## Monitoring

Start Keycloak with `--metrics-enabled=true`; metrics appear on the management interface
(`:9000/metrics`).

| Metric | What it tells you | Suggested alert |
|--------|-------------------|-----------------|
| `scim_outbound_queue_open` | Rows waiting or in progress. | Growing steadily for 30 minutes. |
| `scim_outbound_queue_oldest_age_seconds` | How stale the oldest change is; the access-removal delay. | Above 900 (15 minutes). |
| `scim_outbound_rows_total{result="failed"}` | Changes that need a human. | Any increase. |
| `scim_outbound_rows_total{result="retry"}` | Temporary failures. | Spike per target. |
| `scim_outbound_row_duration_seconds` | Time per row. | Sustained high p95. |
| `scim_outbound_reconcile_total{blocked="true"}` | Reconciliation blocked by the safety brake. | Any. |
| `scim_outbound_reconcile_changes_total` | Drift that events missed. | Large or growing. |

Per-target detail is available from `GET .../targets/{id}/status`.

## Runbook

**Queue growing.** Check `status` and failed rows of each target. `RETRY_WAIT` with 5xx or
timeouts means the application is unhealthy; 429 means it is rate limiting (the plugin honours
`Retry-After`); consider a smaller `batch-size`.

**Failed rows.** Read the sanitised `lastError`. Typical causes: a mapping the application rejects
(400), missing permissions on the SCIM token (403), revoked credentials (401 after refresh). Fix
the cause (configuration, vault secret, application permissions), then `retry` or `retry-all`.

**Reconciliation blocked.** Run a dry run and review `userRemovals` and the reasons (scope change,
deleted users). If the removals are intended, raise `safety.maxRemovalPercent` for one run, run
again, then set it back.

**Rotating a secret.** Update the vault entry. Bearer and Basic secrets are read on every request.
OAuth tokens are cached until expiry; a 401 forces a refresh, or update the target to drop
cached tokens immediately.

**After a Keycloak upgrade.** Confirm the plugin release supports the new version first. After
the upgrade, check the startup log and `status` of each target.

**Disaster recovery.** All plugin state lives in Keycloak's database and is restored with it.
After restoring an older backup, run a reconciliation so targets match Keycloak again.
