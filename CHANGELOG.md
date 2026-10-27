# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-10-27

First release.

### Added

- Outbound SCIM 2.0 provisioning of users (create, update, disable, deactivate or delete).
- Group and membership provisioning, optional per target.
- Several targets per realm with bearer, OAuth 2.0 client credentials or Basic authentication;
  secrets as Keycloak vault references.
- Transactional outbox with an asynchronous, cluster-aware worker, retries with backoff and
  `Retry-After` support, coalescing and per-object ordering.
- Per-target scope by group, custom attribute mapping (including the enterprise extension),
  deprovisioning mode and loop protection.
- Accounts that already exist on a target are never taken over unless they were provisioned for
  that Keycloak user (`externalId`) or the target sets `linkExistingAccounts`.
- Compatibility with servers that page by cursor (RFC 9865) and limit member changes, such as
  AWS IAM Identity Center: member changes in chunks of 100 and member syncs that send only the
  differences.
- Scheduled, cluster-safe reconciliation with dry run, arming and a mass-removal safety brake.
- Admin REST API: target CRUD, test connection, status, failed rows with retry, reconciliation.
- SSRF protection (https only, host allowlist, private address blocking), response size limits,
  no redirects.
- Secret protection against realm admins: targets only reference vault keys with the
  `vault-key-prefix` (default `scim-`), and a masked secret is not kept when its URL moves to
  another host.
- Micrometer metrics.
- Docker-compose demo, documentation, CI, CodeQL, SBOM and release pipeline.

[Unreleased]: https://github.com/shebang-labs/scim-outbound-for-keycloak/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/shebang-labs/scim-outbound-for-keycloak/releases/tag/v0.1.0
