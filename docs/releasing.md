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

Releases are published on GitHub from the start. Maven Central and GPG signing are optional: the
release workflow adds them as soon as the `release` environment holds their four secrets.

1. **GitHub**: create an environment named `release` that only `v*` tags can deploy to, with a
   maintainer as required reviewer. Enable 2FA for all maintainers and protect `main` and `v*`
   tags.

Optional, for Maven Central and signed artifacts:

1. **Maven Central**: claim the `io.shebanglabs` namespace in the
   [Central Portal](https://central.sonatype.com/). It is verified with a DNS `TXT` record on
   `shebanglabs.io` that the portal shows when the namespace is requested. Generate a user token.
2. **Signing key**: create a dedicated GPG key for releases, publish the public key to
   `keys.openpgp.org`, and document its fingerprint in this file.
3. **Secrets**: add `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` (ASCII-armored)
   and `GPG_PASSPHRASE` to the `release` environment.

## Cutting a release

1. Make sure `main` is green, including the integration matrix.
2. Update `CHANGELOG.md`: move entries from *Unreleased* to a new version section with the date.
3. Update the compatibility table in `README.md` if supported Keycloak versions changed.
4. Open a pull request with these changes (`git commit -s -m "chore(release): 0.2.0"`) and merge
   it once all checks pass.
5. Tag the merged commit on `main` and push the tag (only repository admins may create `v*` tags):

   ```bash
   git switch main && git pull
   git tag -a v0.2.0 -m "v0.2.0"
   git push origin v0.2.0
   ```

6. Approve the run of the `release` environment. The workflow sets the version from the tag, runs
   the full test suite and creates a GitHub Release with the jar, SHA-256 checksums, the CycloneDX
   SBOM and a build provenance attestation. With the optional secrets it also signs the artifacts
   (`.asc`) and publishes them to Maven Central.
7. Bump `main` to the next snapshot in a pull request:
   `./mvnw versions:set -DnewVersion=0.3.0-SNAPSHOT`.

## What a release contains

| Artifact | Purpose |
|----------|---------|
| `scim-outbound-for-keycloak-X.Y.Z.jar` | The plugin, to copy into `providers/`. Contains only plugin classes. |
| `.jar.asc` | GPG signature (when Maven Central publishing is configured). |
| `.jar.sha256` | Checksum. |
| `-sbom.json` | CycloneDX SBOM, including the Keycloak libraries the plugin links against. |
| sources and javadoc jars | On Maven Central (when configured). |
| provenance attestation | `gh attestation verify scim-outbound-for-keycloak-X.Y.Z.jar -R <org>/scim-outbound-for-keycloak` |

Builds are reproducible: the build timestamp is fixed (`project.build.outputTimestamp`), so
anyone can rebuild a tag and compare checksums.

## Security releases

Follow [SECURITY.md](../SECURITY.md): fix in a private fork or security advisory, release a
patch version, then publish the advisory with credits.
