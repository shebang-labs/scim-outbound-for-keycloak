# Security model

An identity provider is the most sensitive system in most organisations. This plugin adds two
things to it: **outgoing network calls** and **credentials for other systems**. The design follows
four principles: secure by default, least privilege, least data, and fail safe (when unsure, stop
and alert rather than act).

## Trust boundaries

| Input | From | Trust | Protection |
|-------|------|-------|------------|
| Keycloak events | Keycloak (in-process) | trusted | same JVM |
| Admin REST calls | Keycloak admins | trusted after authentication | admin token; `manage-realm` to change, `view-realm` to read; audited |
| SCIM responses | third-party servers | **untrusted** | TLS verification, no redirects, size cap, strict JSON parsing (duplicate keys rejected, depth and length limits), only known fields read, error text truncated and sanitised |

The plugin opens no port and accepts no webhooks.

## Threats and mitigations

| Threat | Mitigation |
|--------|------------|
| Credential theft from the database or a realm export | Vault references instead of secrets; secrets masked in API responses and admin events; least-privilege tokens; tokens kept in memory only. |
| SSRF through a malicious target URL | Operator host allowlist; HTTPS only; private, loopback, link-local, CGNAT and unique-local addresses blocked; URLs re-checked before every use; redirects never followed; credentials/query/fragment in URLs rejected. |
| Man-in-the-middle | TLS with certificate and hostname verification through Keycloak's truststore; no option to disable it. |
| Malicious or huge responses | 5 MB cap (configurable), bounded reads also for chunked bodies, strict parser limits. |
| Personal data in logs | Logs contain ids, methods, paths without queries and status codes only; bodies are never logged. |
| Personal data in the database | The outbox stores ids only; stored error text is truncated and stripped of control characters, and non-SCIM error bodies are never stored. |
| Cross-realm leakage | Every row, mapping and target carries its realm; the admin API filters by realm; the worker loads data from the row's realm only; covered by integration tests. |
| Mass deactivation by misconfiguration | Deactivate (not delete) by default; reconciliation safety brake; first reconciliation is a dry run; a scope that resolves to no groups fails instead of excluding everybody. |
| Provisioning loops | `loopProtection.ignoredClientIds`. |
| Denial of service against Keycloak | No I/O in transactions or on the login path; bounded batches; single worker thread per node; timeouts; recording failures never break Keycloak actions, and IDs that do not fit the outbox are skipped instead of failing the Keycloak transaction. |
| Tampered release | Signed artifacts, checksums, SBOM and build provenance (see [releasing](releasing.md)). |

## Residual risks

- **Runs inside the IdP.** A bug in the plugin is a bug in Keycloak. Keep the codebase small,
  reviewed, scanned (CodeQL) and with few dependencies (it bundles none).
- **Access-removal delay.** Provisioning is asynchronous; while an application is down, a person
  who left keeps access there. Monitor `scim_outbound_queue_oldest_age_seconds` and keep session
  lifetimes short in applications that also use Keycloak SSO.
- **Upgrade breakage.** Some SPIs used are internal to Keycloak. Follow the compatibility table.
- **SCIM token power.** A leaked SCIM token can create accounts (sometimes admins) in another
  system. Treat it like a database password and rotate it.

## Never

- Send passwords, hashes, OTP secrets or other credentials.
- Make network calls inside a Keycloak transaction or on the login path.
- Disable TLS verification or follow redirects.
- Remove more accounts than the safety limit without an explicit admin decision.
- Log tokens or payloads.
- Accept configuration changes without `manage-realm`.
