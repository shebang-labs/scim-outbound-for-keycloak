# Contributing

Thanks for helping improve SCIM Outbound for Keycloak. This plugin runs inside identity
providers, so we care a lot about correctness, security and small, reviewable changes.

## Ground rules

- Be kind. This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md).
- **Security issues go to [SECURITY.md](SECURITY.md)**, never into public issues or pull requests.
- Discuss larger changes in an issue first, so nobody spends a weekend on something that will not
  be merged.

## Developer Certificate of Origin (DCO)

All commits must be signed off. By signing off you certify the
[Developer Certificate of Origin](https://developercertificate.org/): that you wrote the change or
otherwise have the right to submit it under the project's license (Apache-2.0).

```bash
git commit -s -m "fix(worker): handle 412 from servers that use ETags"
```

This adds a line like `Signed-off-by: Jane Doe <jane@example.com>` that must match the commit
author. CI rejects pull requests with unsigned commits. To sign off commits you already made:

```bash
git rebase --signoff main
```

## Development setup

You need JDK 21 and Docker (integration tests start Keycloak and WireMock containers). The
Maven wrapper downloads the right Maven version.

```bash
./mvnw -DskipITs verify      # fast loop: compile, license check, unit tests
./mvnw verify                # everything, including integration tests (a few minutes)
./mvnw verify -Dit.test=UserProvisioningIT -Dtest=NoUnit -Dsurefire.failIfNoSpecifiedTests=false
./mvnw license:format        # add missing SPDX headers
```

Run against another Keycloak version with `-Dkeycloak.version=26.7.5`.

## Project rules

These keep the plugin safe to run inside Keycloak. Reviews check them.

1. **Never make network calls inside a Keycloak transaction or on the login path.** The event
   listener only writes outbox rows; HTTP happens in the worker, outside transactions.
2. **Never bundle libraries Keycloak ships.** Keycloak dependencies (and Jackson, Apache
   HttpClient, JBoss Logging, Micrometer) are `provided`. The enforcer plugin fails the build
   otherwise. New runtime dependencies need a strong reason and an Apache-2.0-compatible license.
3. **Never log secrets, tokens or personal data.** Log ids, operations and status codes. Error
   text from SCIM servers must go through `ScimResponse.errorSummary()`.
4. **Every behaviour change comes with tests.** Unit tests for logic (WireMock for exact SCIM
   requests), integration tests (`*IT`) for anything that depends on Keycloak.
5. **Secure defaults stay secure.** New options that weaken security must be opt-in and documented.
6. **Liquibase changeSets are immutable once released.** Add a new changeSet instead.

## Code style

- Java 21, 4-space indentation, 120 columns, `.editorconfig` is the reference.
- Every source file starts with the SPDX header (`./mvnw license:format` adds it).
- Prefer small classes with one job; keep Keycloak-specific code out of pure logic so it can be
  unit-tested.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`,
`test:`, `build:`, `ci:`, `refactor:`, `chore:`, optionally with a scope such as
`feat(reconcile):`. Keep commits focused; one logical change per commit.

## Pull requests

- Fill in the pull request template.
- Make sure `./mvnw verify` passes locally.
- Update `CHANGELOG.md` (Unreleased section) and the docs for user-visible changes.
- Maintainers squash or rebase-merge after review; at least one maintainer approval is required.
