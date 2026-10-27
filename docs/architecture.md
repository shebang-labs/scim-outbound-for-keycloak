# Architecture

This document explains how the plugin works: where it sits, how a change travels from Keycloak to
an application, and why it is built this way. It is the implemented version of the original
design report.

## Where it sits

```
 admins, users, LDAP, IdPs ──► Keycloak ──► [ SCIM Outbound plugin ] ──HTTPS + SCIM──► App A, App B, ...
                                   │                 │
                                   └── Keycloak DB ◄─┘  (+ outbox, ID map, targets)
```

The plugin is a Java extension (one jar in `providers/`) running inside the Keycloak process. It
needs no extra infrastructure. Applications never call the plugin; the plugin only calls them.

## Components

| Package | Responsibility |
|---------|----------------|
| `events` | `ScimEventListenerProvider` (user and admin events) and `ModelEventListener` (model events: user removal, group create/update/remove, membership join/leave, realm removal) turn Keycloak changes into `Change`s. |
| `outbox` | `ChangeRecorder` writes one outbox row per interested target, inside the current transaction. |
| `jpa` | Entities, Liquibase changelog and repositories for the three tables. |
| `worker` | `OutboxWorker` claims rows; `SyncLoader` reads current Keycloak state; `SyncExecutor` performs the SCIM calls. |
| `http` / `auth` | Bounded, redirect-free SCIM transport and protocol client; bearer, Basic and OAuth2 client credentials. |
| `mapping` | Keycloak user/group to SCIM resources, using an attribute allowlist. |
| `reconcile` | Full comparison and repair, with dry run and safety brake. |
| `admin` | Realm admin REST API. |
| `spi` | The `scim-outbound` SPI; its factory owns server-wide state and schedules background work. |

### Tables

| Table | Content |
|-------|---------|
| `SCIM_OUTBOUND_TARGET` | Target configuration per realm (JSON with vault references, never plain secrets in production). |
| `SCIM_OUTBOUND_OUTBOX` | Durable queue: realm, target, resource type and id, operation, status, attempts, next attempt, sanitised last error. **Ids only, no personal data.** |
| `SCIM_OUTBOUND_ID_MAP` | Keycloak id to remote SCIM id, per target. |

## The life of a change

1. An admin updates user Alice. Keycloak opens a transaction and updates the user.
2. Keycloak calls the event listener on the same thread. The listener writes one outbox row per
   enabled target (`USER / UPSERT / alice-id / PENDING`). Nothing else.
3. Keycloak commits: the change and the outbox rows are saved **together**. If the change rolls
   back, the rows vanish too.
4. The admin gets the response. No application has been contacted yet.
5. Within a few seconds the worker (on one cluster node) claims the row in a short transaction,
   loads Alice's **current** state and her remote id in another short transaction, sends a SCIM
   PATCH (or PUT) **with no transaction open**, and records the result in a third transaction.

Because the worker reads current data, several quick edits collapse into one up-to-date request,
and the outbox never needs to store personal data.

### Which signals trigger what

| In Keycloak | Signal | Queued operation |
|-------------|--------|------------------|
| Admin creates, updates, enables/disables a user | admin event `USER` | `USER UPSERT` |
| Admin deletes a user; user deletes own account; LDAP removal | admin event / `DELETE_ACCOUNT` / `UserRemovedEvent` | `USER DELETE` |
| User registers, updates profile or email, first broker login | user events | `USER UPSERT` |
| Group created, renamed, moved, deleted | admin event `GROUP` / group model events | `GROUP UPSERT` / `GROUP DELETE` |
| User joins or leaves a group | admin event `GROUP_MEMBERSHIP` / `GroupMemberJoin/LeaveEvent` | `MEMBER_ADD` / `MEMBER_REMOVE` (+ `USER UPSERT` for scoped targets) |
| Changes without events (some LDAP syncs, edits in the app) | none | repaired by reconciliation |

Signals from both admin events and model events for the same change are coalesced in the outbox.

## Outbox row states

```
PENDING ──claim──► IN_PROGRESS ──2xx──► DONE
   ▲                   │  │
   │      timeout,5xx,429  └──4xx / gave up──► FAILED ──admin retry──► PENDING
   │                   ▼
   └──backoff elapsed── RETRY_WAIT
PENDING ──newer change for the same object──► SUPERSEDED
```

- **Coalescing**: a new `UPSERT` supersedes older unclaimed `UPSERT`s of the same object; a
  `DELETE` supersedes pending `UPSERT`s.
- **Ordering**: a row is only claimed when no older open row exists for the same object, so a
  delete never overtakes the create before it.
- **Priority**: deprovisioning (delete, disable, membership removal) is sent first.
- **Retry schedule**: 30 s, 1 min, 2 min, 5 min, 10 min, 30 min, then hourly (±20 % jitter);
  `Retry-After` wins (capped at 6 h). After `max-retry-hours` the row becomes `FAILED`.
- **Crash recovery**: rows `IN_PROGRESS` for longer than `claim-timeout-seconds` return to
  `PENDING`; idempotent operations make the repeat harmless.
- **Housekeeping**: delivered rows are purged after `retention-days`, failed rows after
  `failed-retention-days`.

## SCIM semantics

| Situation | Behaviour |
|-----------|-----------|
| Unknown user | `GET /Users?filter=userName eq "..."`; if found, link under the same rule as a `409`, otherwise `POST` |
| `409` on create | look up by `userName`; link and update only if its `externalId` is the Keycloak ID (or `linkExistingAccounts` is on), otherwise fail and leave it untouched |
| Known user | `PATCH` with `replace` operations (or `PUT` if `/ServiceProviderConfig` says PATCH is unsupported) |
| `404` on update | unlink, look up, re-create |
| Deprovision | `PATCH active=false` (default) or `DELETE`; `404` counts as success |
| User leaves the target's scope | deprovisioned, link kept (deactivate mode) so re-entry reactivates the same account |
| Groups | `displayName` = group name, `externalId` = group id; members added/removed with PATCH (`members[value eq "id"]`), full replace during reconciliation |
| `401` | credentials refreshed once (OAuth), then retried once; a second 401 is reported |
| `429`, `5xx`, timeouts | retried with backoff |
| other `4xx` | `FAILED`, sanitised error stored for the admin |

## Transactions and connections

- **Never a database transaction across network I/O.** Each worker step is its own
  `KeycloakModelUtils.runJobInTransaction` call.
- The plugin uses Keycloak's JPA `EntityManager` and connection pool, and Keycloak's pooled
  Apache HttpClient (`HttpClientProvider`), so the operator's truststore and proxy settings apply.
  Per request it sets connect/read timeouts, disables redirects and caps the response size.
- OAuth tokens are cached in memory per node, never persisted.

## Running in a cluster

Every node runs the listeners (any node can receive an admin request). Background work is
scheduled with Keycloak's `ClusterAwareScheduledTaskRunner`, so one node runs each worker round
and each reconciliation. Claiming is a conditional update (`... where status in (PENDING,
RETRY_WAIT)`), so even overlapping rounds cannot send a row twice. The timer thread only hands
work to the plugin's own worker thread, so slow applications never delay Keycloak's scheduled
tasks.

## Reconciliation

For each target: list remote users (paged, outside transactions), read Keycloak users in scope in
pages, compare using the ID map, and queue `UPSERT`s for missing or drifted users, deprovisioning
for out-of-scope or orphaned **managed** accounts, and group upserts plus full member syncs.
Remote accounts that the plugin never created or linked are counted but never touched.

Safety: if removals exceed `removalFloor` and `maxRemovalPercent` of managed accounts, the run is
**blocked and changes nothing**. Until an admin completes one real run, scheduled runs are dry
runs (`firstReconcileDryRun`).

## Relation to Keycloak's native SCIM

Keycloak 26.8's SCIM API is inbound (`/realms/{realm}/scim/v2`). Both can run side by side. If a
system pushes users into Keycloak and is also a target, add its client id to the target's
`loopProtection.ignoredClientIds` so its own changes are not sent back to it.
