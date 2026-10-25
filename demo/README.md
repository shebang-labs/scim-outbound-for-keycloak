# Demo

Runs Keycloak 26.8 with the plugin, PostgreSQL, and a WireMock server that plays a SCIM
application, so you can watch exactly which SCIM requests Keycloak changes produce.

> Demo only: it allows plain http and private addresses and uses demo credentials.

## Run it

```bash
./mvnw -DskipTests package        # from the repository root
cd demo
docker compose up -d
./setup.sh                        # creates a target and provisions the existing users
```

`setup.sh` creates a target `demo-app` in realm `demo` (bearer token from the files vault:
`vault/demo_scim-token`), tests the connection and runs a reconciliation that creates `alice`,
`bob` and the groups `engineering` and `sales` in the demo app.

## Look around

- SCIM requests received by the demo app: <http://localhost:8081/__admin/requests>
- Keycloak admin console: <http://localhost:8080/admin> (`admin` / `admin`), realm `demo`
- Metrics: <http://localhost:9000/metrics> (search for `scim_outbound`)

Then try:

1. Edit Alice's email in the admin console: a `PATCH /scim/v2/Users/demo-alice` appears.
2. Disable Bob: a PATCH with `active: false`.
3. Add Bob to `engineering`: a PATCH adding him to the group's members.
4. Register a new user at <http://localhost:8080/realms/demo/account>: a `POST /scim/v2/Users`.
5. Delete a user: a PATCH with `active: false` (the safe default; set `"deprovision": "DELETE"`
   on the target to send a DELETE instead).
6. Stop the demo app (`docker compose stop scim-app`), make changes, and watch the status endpoint:
   rows wait in `RETRY_WAIT` while Keycloak keeps working; start it again and they are delivered.

The plugin logs at debug level in this demo (`docker compose logs -f keycloak`); note that it
logs methods, paths and status codes, never bodies or tokens.

## Clean up

```bash
docker compose down -v
```
