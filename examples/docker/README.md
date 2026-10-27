# Running Keycloak with the plugin in Docker

The [Dockerfile](Dockerfile) builds a Keycloak image with the plugin already installed. Everything
happens inside Docker: the server needs Docker (with Compose) and nothing else, no Java or Maven.
The plugin jar is downloaded from the GitHub release and the build stops if its SHA-256 checksum
does not match the published one.

| File | Purpose |
|------|---------|
| `Dockerfile` | Keycloak 26.8.1 + plugin 0.1.0, built with `kc.sh build` and started with `start --optimized`. |
| `compose.yml` | That image with PostgreSQL, a files vault and the plugin's server options. |
| `.env.example` | The values `compose.yml` needs. Copy it to `.env`. |

## Start it

On the server, in a folder containing these three files:

```bash
cp .env.example .env && chmod 600 .env    # then fill in .env
mkdir -p vault
docker compose up -d --build
docker compose logs -f keycloak            # wait for "Keycloak ... started"
```

The log shows the plugin's settings when it starts:

```
scim-outbound: worker every 5s, ..., vault key prefix=scim-, host allowlist=[scim.us-east-1.amazonaws.com]
```

Put your reverse proxy (TLS) in front of `127.0.0.1:8080` and set `KC_HOSTNAME` to the public URL.
Then enable the plugin per realm and create targets as described in the
[README](../../README.md#quick-start) and [configuration guide](../../docs/configuration.md).

## Target secrets

Targets reference secrets as `${vault.scim-<key>}`; the files vault reads them from
`vault/<realm>_scim-<key>`:

```bash
printf '%s' "$TOKEN" > vault/acme_scim-aws-token     # ${vault.scim-aws-token} in realm acme
sudo chown 1000 vault/acme_scim-aws-token            # the keycloak user inside the container
sudo chmod 400 vault/acme_scim-aws-token             # and nobody else
```

The file is read on every use, so replacing it rotates the secret without a restart. With a secret
manager such as 1Password, have its tooling write the file, for example
`op read "op://<vault>/<item>/token" > vault/acme_scim-aws-token`.

## Already running Keycloak in Docker?

Keep your setup and only replace the image: point your service at this Dockerfile (`build:`) or at
an image built from it, add the `vault` volume and the `KC_VAULT_DIR` and
`KC_SPI_SCIM_OUTBOUND__DEFAULT__ALLOWED_HOSTS` variables. Two things to carry over into the
Dockerfile's builder stage, since `kc.sh build` fixes them into the image:

- your build-time options, such as `KC_DB` (if you do not use PostgreSQL) and `KC_FEATURES`;
- any other providers or themes you copy into the image today.

The database stays as it is: on its first start the plugin adds its own three tables.

## Servers without internet access

Build the image where GitHub is reachable (a workstation or CI), push it to your registry, and only
pull it on the server:

```bash
docker build -t registry.example.com/keycloak-scim:26.8.1-0.1.0 .
docker push registry.example.com/keycloak-scim:26.8.1-0.1.0
```

In `compose.yml`, set `image:` to that name and remove the `build:` line.

## Upgrading

- **Plugin**: change `PLUGIN_VERSION` and `PLUGIN_SHA256` (from the release's `.jar.sha256` file)
  and rebuild.
- **Keycloak**: change `KEYCLOAK_VERSION` to a version in the
  [compatibility table](../../README.md#compatibility) and rebuild.

Change the image tag in `compose.yml` with either, then `docker compose up -d --build`.
