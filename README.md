# SCIM Outbound for Keycloak

Push users and groups from [Keycloak](https://www.keycloak.org/) to any application that speaks
**SCIM 2.0** ([RFC 7643](https://www.rfc-editor.org/rfc/rfc7643) /
[RFC 7644](https://www.rfc-editor.org/rfc/rfc7644)).

When someone is created, changed, disabled or removed in Keycloak, this extension tells every
connected application about it, so accounts appear where people need them, stay up to date, and
are switched off everywhere when someone leaves.

> **Not an official Keycloak project.** This is an independent community extension. It is not
> affiliated with or endorsed by the Keycloak project. Keycloak 26.8 ships native SCIM support for
> the *inbound* direction (systems pushing users into Keycloak); this extension adds the
> *outbound* direction.

## Features

- **Users**: create, update, disable and deprovision (deactivate by default, or delete).
- **Groups and membership** (optional per target), including group rename and delete.
- **Several targets per realm**, each with its own credentials, scope and attribute mapping.
- **Authentication**: static bearer token, OAuth 2.0 client credentials, HTTP Basic. Secrets are
  stored as Keycloak vault references (`${vault.scim-my-key}`).
- **Reliable delivery**: a transactional outbox in Keycloak's database. Changes are recorded in
  the same transaction as the Keycloak change and sent afterwards by a background worker with
  retries, backoff and `Retry-After` support. **A slow or broken application never slows down
  or breaks logins or admin actions.**
- **Correct SCIM**: look up before create, 409 becomes a link to the account the plugin created
  (never a takeover of someone else's account, unless allowed), PATCH with PUT fallback
  (discovered through `/ServiceProviderConfig`), 404 handling, idempotent retries, per-object
  ordering and coalescing of rapid edits.
- **Reconciliation**: scheduled, cluster-safe comparison that provisions existing users and repairs
  drift, with a **dry run** and a **mass-removal safety brake**.
- **Failed-operation tracking** with manual retry through the admin REST API.
- **Cluster-safe**: one node sends per round; claims are atomic so a change is never sent twice
  by two nodes.
- **Secure by default**: HTTPS only, TLS verification through Keycloak's truststore, no
  redirects, operator host allowlist and private-address blocking (SSRF protection), attribute
  allowlist, passwords never sent, no tokens or personal data in logs.
- **Metrics** through Keycloak's Micrometer endpoint.

## Compatibility

| Plugin version | Keycloak (Quarkus distribution) | Java |
|----------------|---------------------------------|------|
| 0.1.x          | 26.6.x, 26.7.x, 26.8.x          | 21+  |

The plugin implements some Keycloak SPIs that Keycloak marks as internal (event listener,
JPA entity provider, admin REST extension). They can change between Keycloak minor releases, so
every release is tested against the versions in this table. Check it before upgrading Keycloak.

## Quick start

```bash
# 1. Build (or download a release and verify its checksum/signature)
./mvnw -DskipITs package

# 2. Install into Keycloak and rebuild the optimized server
cp target/scim-outbound-for-keycloak-*.jar /opt/keycloak/providers/
/opt/keycloak/bin/kc.sh build

# 3. Start Keycloak (optionally restrict where targets may point)
/opt/keycloak/bin/kc.sh start --optimized \
  --spi-scim-outbound--default--allowed-hosts='*.example.com'
```

4. In the admin console, open **Realm settings → Events → Event listeners** and add
   `scim-outbound`.
5. Create a target through the admin REST API (an admin console page is on the roadmap):

```bash
TOKEN=...   # an access token of an admin with manage-realm in this realm
curl -X POST "https://keycloak.example.com/admin/realms/acme/scim-outbound/targets" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "name": "app-a",
    "baseUrl": "https://app-a.example.com/scim/v2",
    "auth": { "type": "BEARER", "token": "${vault.scim-app-a-token}" },
    "syncGroups": true
  }'
```

6. Check the connection, then provision existing users with a reconciliation (dry run first):

```bash
curl -X POST ".../scim-outbound/targets/{id}/test" -H "Authorization: Bearer $TOKEN"
curl -X POST ".../scim-outbound/targets/{id}/reconcile?dryRun=true" -H "Authorization: Bearer $TOKEN"
curl ".../scim-outbound/targets/{id}/status" -H "Authorization: Bearer $TOKEN"
curl -X POST ".../scim-outbound/targets/{id}/reconcile?dryRun=false" -H "Authorization: Bearer $TOKEN"
```

Try everything locally with the [docker-compose demo](demo/README.md).

## Documentation

| Topic | Document |
|-------|----------|
| How it works (outbox, transactions, cluster) | [docs/architecture.md](docs/architecture.md) |
| Target configuration and server options | [docs/configuration.md](docs/configuration.md) |
| Admin REST API | [docs/admin-api.md](docs/admin-api.md) |
| Monitoring and runbook | [docs/operations.md](docs/operations.md) |
| Troubleshooting | [docs/troubleshooting.md](docs/troubleshooting.md) |
| Security model and risks | [docs/security.md](docs/security.md) |
| Releasing | [docs/releasing.md](docs/releasing.md) |

## Building and testing

Requirements: JDK 21, Docker (for integration tests). The Maven wrapper downloads Maven.

```bash
./mvnw verify                 # unit + integration tests (starts Keycloak in Docker)
./mvnw -DskipITs verify       # unit tests only
./mvnw verify -Dkeycloak.version=26.7.5   # test against another Keycloak version
```

The build also checks license headers, enforces that no Keycloak-provided library is bundled,
writes a JaCoCo coverage report (`target/site/jacoco`) and a CycloneDX SBOM (`target/*-sbom.json`).

## Contributing

Contributions are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md): every commit needs a
[DCO](https://developercertificate.org/) sign-off (`git commit -s`). Report security issues
privately as described in [SECURITY.md](SECURITY.md), never in public issues.

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).

## License

[Apache License 2.0](LICENSE). "Keycloak" is a trademark of its respective owners and is used here
only to describe compatibility.
