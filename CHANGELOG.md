# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Outbound SCIM 2.0 provisioning of users (create, update, disable, deactivate or delete).
- Group and membership provisioning, optional per target.
- Several targets per realm with bearer, OAuth 2.0 client credentials or Basic authentication;
  secrets as Keycloak vault references.
- Transactional outbox with an asynchronous, cluster-aware worker, retries with backoff and
  `Retry-After` support, coalescing and per-object ordering.
- Per-target scope by group, custom attribute mapping (including the enterprise extension),
  deprovisioning mode and loop protection.
- Scheduled, cluster-safe reconciliation with dry run, arming and a mass-removal safety brake.
- Admin REST API: target CRUD, test connection, status, failed rows with retry, reconciliation.
- SSRF protection (https only, host allowlist, private address blocking), response size limits,
  no redirects.
- Micrometer metrics.
- Docker-compose demo, documentation, CI, CodeQL, SBOM and release pipeline.

[Unreleased]: https://github.com/shebang-labs/scim-outbound-for-keycloak/commits/main
