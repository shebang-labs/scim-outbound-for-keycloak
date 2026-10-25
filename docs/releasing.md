# Releasing

Releases are cut from `main` by pushing a tag. The [release workflow](../.github/workflows/release.yml)
does the rest.

## Versioning

[Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- **PATCH**: bug and security fixes, no configuration or database changes that need attention.
- **MINOR**: new features, new supported Keycloak versions, new Liquibase changeSets (forward
  migrations only).
- **MAJOR**: breaking changes to configuration, the admin API, or dropped Keycloak versions.

`main` always carries the next `-SNAPSHOT` version.

## One-time setup (maintainers)

1. **Maven Central**: claim the `groupId` namespace in the
   [Central Portal](https://central.sonatype.com/) (for `io.github.<org>` this is verified through
   the GitHub organisation). Generate a user token.
2. **Signing key**: create a dedicated GPG key for releases, publish the public key to
   `keys.openpgp.org`, and document its fingerprint in this file.
3. **GitHub**: create an environment named `release` (restricted to maintainers, required
   reviewers recommended) with the secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
   `GPG_PRIVATE_KEY` (ASCII-armored) and `GPG_PASSPHRASE`. Enable 2FA for all maintainers and
   protect `main` and `v*` tags.

> Before the first public release, check that the `groupId` in `pom.xml`
> (`io.shebanglabs`) and the repository URLs match the organisation that will publish it.

## Cutting a release

1. Make sure `main` is green, including the integration matrix.
2. Update `CHANGELOG.md`: move entries from *Unreleased* to a new version section with the date.
3. Update the compatibility table in `README.md` if supported Keycloak versions changed.
4. Commit (`git commit -s -m "chore(release): 0.2.0"`), then tag and push:

   ```bash
   git tag -s v0.2.0 -m "v0.2.0"
   git push origin main v0.2.0
   ```

5. The workflow sets the version from the tag, runs the full test suite, signs and publishes the
   artifacts to Maven Central, and creates a GitHub Release with the jar, `.asc` signature,
   SHA-256 checksums, the CycloneDX SBOM and a build provenance attestation.
6. Bump `main` to the next snapshot: `./mvnw versions:set -DnewVersion=0.3.0-SNAPSHOT`.

## What a release contains

| Artifact | Purpose |
|----------|---------|
| `scim-outbound-for-keycloak-X.Y.Z.jar` | The plugin, to copy into `providers/`. Contains only plugin classes. |
| `.jar.asc` | GPG signature. |
| `.jar.sha256` | Checksum. |
| `-sbom.json` | CycloneDX SBOM, including the Keycloak libraries the plugin links against. |
| sources and javadoc jars | On Maven Central. |
| provenance attestation | `gh attestation verify scim-outbound-for-keycloak-X.Y.Z.jar -R <org>/scim-outbound-for-keycloak` |

Builds are reproducible: the build timestamp is fixed (`project.build.outputTimestamp`), so
anyone can rebuild a tag and compare checksums.

## Security releases

Follow [SECURITY.md](../SECURITY.md): fix in a private fork or security advisory, release a
patch version, then publish the advisory with credits.
