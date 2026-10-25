# Security Policy

This plugin runs inside Keycloak with the same privileges as Keycloak itself, sends personal data
to third-party applications and holds credentials that can create or delete accounts in them. We
treat security reports with priority.

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | yes       |

Only the latest minor release receives security fixes.

## Reporting a vulnerability

**Do not open a public issue.** Please report privately:

1. Preferred: GitHub's **private vulnerability reporting** ("Report a vulnerability" under the
   repository's *Security* tab).
2. Or email the maintainers at **ghazy@shebanglabs.io** with "SECURITY" in the subject.

Please include the affected version, Keycloak version, a description of the issue and its impact,
and steps to reproduce. Do not include real personal data or production secrets.

What to expect:

- acknowledgement within **3 working days**;
- an initial assessment within **10 working days**;
- a fix and a coordinated disclosure date agreed with you. We credit reporters in the advisory
  unless you prefer otherwise.

## Scope

In scope: the plugin code, its default configuration, its build and release artifacts.

Out of scope: vulnerabilities in Keycloak itself (report those to the
[Keycloak project](https://github.com/keycloak/keycloak/security/policy)) and in downstream SCIM
applications.

## Hardening checklist for operators

- Restrict targets with `--spi-scim-outbound--default--allowed-hosts`.
- Keep `allow-http`, `allow-private-addresses` and `allow-plaintext-secrets` off in production.
- Store target secrets in the Keycloak vault and give SCIM tokens the least privilege the
  application allows.
- Only grant `manage-realm` to admins who may configure provisioning.
- Verify release checksums and signatures before installing the jar.
- Monitor `scim_outbound_queue_oldest_age_seconds` so delayed deprovisioning is noticed.

See [docs/security.md](docs/security.md) for the threat model.
