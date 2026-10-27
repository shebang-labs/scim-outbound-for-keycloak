# Configuration

There are two levels of configuration:

- **Server options**, chosen by the Keycloak operator, apply to all realms and limit what realm
  admins can do.
- **Targets**, configured per realm by admins with `manage-realm`, through the
  [admin REST API](admin-api.md).

## Server options

Pass them like any Keycloak SPI option, on the command line, as environment variables or in
`keycloak.conf`:

```bash
kc.sh start --spi-scim-outbound--default--allowed-hosts='*.example.com,scim.partner.test'
# environment variable form
KC_SPI_SCIM_OUTBOUND__DEFAULT__ALLOWED_HOSTS='*.example.com'
```

| Option | Default | Description |
|--------|---------|-------------|
| `allowed-hosts` | *(empty: any public host)* | Comma-separated host allowlist for target and token URLs. Entries are exact names or `*.suffix`. Explicitly allowlisted hosts may resolve to private addresses. |
| `allow-http` | `false` | Allow `http://` URLs. For local testing only. |
| `allow-private-addresses` | `false` | Allow non-allowlisted hosts that resolve to loopback, private, link-local (including cloud metadata), CGNAT or unique-local addresses. |
| `allow-plaintext-secrets` | `false` | Accept secrets that are not `${vault.*}` references. |
| `vault-key-prefix` | `scim-` | Vault keys a target may reference must start with this, so a realm admin cannot send the realm's other vault secrets (LDAP bind password, identity provider secrets) to a SCIM server. `*` allows any key. |
| `worker-interval-seconds` | `5` | How often the outbox worker runs. |
| `batch-size` | `50` | Maximum rows claimed per worker round. |
| `reconcile-interval-minutes` | `1440` | Scheduled reconciliation interval; `0` disables it. |
| `connect-timeout-ms` | `5000` | TCP connect timeout. |
| `read-timeout-ms` | `30000` | Socket read timeout. |
| `max-response-bytes` | `5242880` | Larger responses are rejected. |
| `max-retry-hours` | `24` | A change still failing after this long becomes `FAILED`. |
| `claim-timeout-seconds` | `300` | Claimed rows older than this are released (node crash recovery). |
| `retention-days` | `7` | Days to keep delivered and superseded rows. |
| `failed-retention-days` | `30` | Days to keep failed rows. |

The plugin logs its effective settings at startup (`scim-outbound: worker every ...`).

## Enabling the event listener

In each realm: **Realm settings → Events → Event listeners → add `scim-outbound`**. Model-level
changes (user removal, group changes, membership) are captured even without it, but user
creation and updates need the listener.

## Target configuration

```json
{
  "name": "app-a",
  "enabled": true,
  "baseUrl": "https://app-a.example.com/scim/v2",
  "auth": {
    "type": "OAUTH2_CLIENT_CREDENTIALS",
    "tokenUrl": "https://app-a.example.com/oauth/token",
    "clientId": "keycloak-provisioner",
    "clientSecret": "${vault.scim-app-a-client-secret}",
    "scope": "scim.write"
  },
  "deprovision": "DEACTIVATE",
  "syncGroups": true,
  "scope": { "groups": ["/engineering", "/support"] },
  "customAttributes": {
    "enterprise.department": "department",
    "title": "jobTitle"
  },
  "safety": { "maxRemovalPercent": 5, "removalFloor": 10, "firstReconcileDryRun": true },
  "loopProtection": { "ignoredClientIds": ["hr-system"] }
}
```

| Field | Default | Description |
|-------|---------|-------------|
| `name` | required | 1-64 characters: letters, digits, `.`, `_`, `-`. Unique per realm. |
| `enabled` | `true` | Disabled targets receive nothing; pending rows are skipped. |
| `baseUrl` | required | SCIM base URL (without `/Users`). Checked against the server's host policy. |
| `auth.type` | required | `BEARER`, `OAUTH2_CLIENT_CREDENTIALS` or `BASIC`. |
| `auth.token` | for `BEARER` | Vault reference to the token. |
| `auth.username`, `auth.password` | for `BASIC` | Password as a vault reference. |
| `auth.tokenUrl`, `auth.clientId`, `auth.clientSecret`, `auth.scope` | for OAuth2 | Client credentials grant with `client_secret_basic`. |
| `deprovision` | `DEACTIVATE` | `DEACTIVATE` (`active=false`, reversible) or `DELETE`. |
| `syncGroups` | `false` | Also provision groups and memberships. Servers that do not return group members (such as AWS IAM Identity Center) are asked for the users that are members instead; a server that can do neither only gets missing members added by reconciliation, and removals rely on membership events. |
| `scope.groups` | *(all users)* | Group paths. Only members (including through sub-groups) are provisioned; with group sync, only these groups and their sub-groups. Users who leave the scope are deprovisioned. |
| `customAttributes` | *(none)* | Map of SCIM attribute to Keycloak user attribute. Supported SCIM attributes: `title`, `preferredLanguage`, `locale`, `enterprise.employeeNumber`, `enterprise.costCenter`, `enterprise.organization`, `enterprise.division`, `enterprise.department`. |
| `safety.maxRemovalPercent` | `5` | Reconciliation removals above the floor may not exceed this share of managed accounts. |
| `safety.removalFloor` | `10` | Removals always allowed per run. |
| `safety.firstReconcileDryRun` | `true` | Scheduled reconciliation only reports until an admin has completed one real run. |
| `loopProtection.ignoredClientIds` | *(none)* | Changes made through these OAuth clients (bearer token `azp`) are not sent to this target. |
| `linkExistingAccounts` | `false` | See [Existing accounts on the target](#existing-accounts-on-the-target). |

### Existing accounts on the target

Before creating a user, the plugin looks it up by `userName` (groups by `displayName`). When it
finds one, it only links to it if the account's `externalId` is the Keycloak ID of that user or
group, which means this plugin created it (for example before a crash that lost the link).

Any other match is left untouched and the change fails with "left untouched". Linking would
otherwise overwrite the account, and later deactivate or delete it together with the Keycloak user:
a test user named like a real one would take the real account down with it. Rename or remove
the remote account, or, when the target already holds the same people (for example when it was
provisioned by hand before), set `"linkExistingAccounts": true` for the first reconciliation and
switch it off afterwards.


### Default attribute mapping

| Keycloak | SCIM |
|----------|------|
| user id | `externalId` |
| username | `userName` |
| first / last name | `name.givenName` / `name.familyName` |
| first + last name (or username) | `displayName` |
| email | `emails[type=work, primary=true]` |
| enabled | `active` |
| password, OTP, credentials | **never sent** |

### Secrets and the vault

Configure a [Keycloak vault](https://www.keycloak.org/server/vault) and reference secrets as
`${vault.<key>}`. They are resolved at the moment of use and never returned by the API (the
API shows `**********`; sending that value back on update keeps the stored secret, as long as
the URL it is sent to keeps its host: when `baseUrl` or `auth.tokenUrl` moves to another host, the
secret has to be entered again, so nobody can redirect a secret they cannot see). Plain-text
secrets are rejected unless the operator sets `allow-plaintext-secrets=true`. Keys must start with
the `vault-key-prefix` (default `scim-`).

With the files vault, `${vault.scim-app-a-token}` in realm `acme` is read from the file
`acme_scim-app-a-token` in the vault directory.
