# Office IoT Monitoring & Control — Implementation Plan

**Purpose:** A phase-by-phase build plan for Claude Code, covering everything in the *System Design* and *REST API Design / OpenAPI* documents.
**Target stack:** Spring Boot · PostgreSQL · MQTT (Mosquitto/EMQX) · Spring Security (OAuth2 + JWT) · optional Redis.
**Topology:** Modular monolith, one deployable, extract-ready module seams.

---

## How to use this plan

- Phases are ordered by dependency. **Do not start a phase until the previous one meets its Definition of Done (DoD).** Each phase leaves the system compiling, migrating, and passing its tests.
- Every phase lists: **Goal · Deliverables (what is achieved) · Endpoints/Topics · Modules · Data · Load-bearing decisions to honor · DoD · Tests.**
- The two design documents remain authoritative: the **Data Spec** wins for wire formats; the **System Design** wins for structure/decisions; the **API Design / OpenAPI** wins for REST contracts.
- The **Coverage Matrix** at the end maps every one of the 44 REST operations and every load-bearing decision to a phase — use it to confirm nothing is dropped.

### Non-negotiable invariants (apply in every phase)
1. **No persistence detail on the wire** — never expose `passwordHash`, `clientSecretHash`, raw partition row PKs (`telemetry.id`), or internal IDs. DTOs only.
2. **JSON is camelCase; timestamps are ISO-8601 UTC; IDs are opaque strings.**
3. **One error shape everywhere** — RFC 9457 Problem Details. Never return `200` with an error body.
4. **Module boundaries are real** — modules talk through service interfaces, never each other's repositories. Only `telemetry`, `command`, `audit`, `health` own write access to their own tables. The `rules → command/alert` hop goes through a published interface.
5. **RBAC + scopes enforced at the edge** — `@PreAuthorize` on every endpoint per the contract; device endpoints gated by scope.
6. **Reads of partitioned tables (`telemetry`, `audit_logs`) require a bounded time window** — reject unbounded/oversized queries with `422`.
7. **Security-relevant actions are audited** — login, device register/delete, credential rotation, rule change, command execution, role change.

### Definition-of-Done template (every phase)
- Code compiles; app boots on the `local` profile against Docker Compose.
- DB migrations apply cleanly forward (and are reversible or forward-fix documented).
- Unit tests for service logic + integration tests (Testcontainers: Postgres, and MQTT broker where relevant) green.
- New endpoints match the OpenAPI contract (status codes, DTO shapes, role/scope gates) — verified by contract tests.
- New security-relevant actions write audit entries.
- OpenAPI doc regenerated; no contract drift.

---

## Phase dependency overview

```mermaid
flowchart TB
    P0["Phase 0\nFoundation & Scaffolding"] --> P1["Phase 1\nPersistence & Data Model"]
    P1 --> P2["Phase 2\nSecurity & Identity"]
    P2 --> P25["Phase 2.5\nToken Revocation & Denylist"]
    P25 --> P3["Phase 3\nDevice Registry & Lifecycle"]
    P25 --> P4["Phase 4\nMQTT Adapter + Telemetry + Current State"]
    P3 --> P4
    P4 --> P5["Phase 5\nHeartbeat / Health / Connectivity"]
    P4 --> P6["Phase 6\nCommands + Ack + Timeout Sweeper"]
    P3 --> P6
    P4 --> P7["Phase 7\nRule Engine"]
    P6 --> P7
    P7 --> P8["Phase 8\nAlerts"]
    P1 --> P9["Phase 9\nAudit Query API"]
    P8 --> P10["Phase 10\nHardening · Observability · Ops · Deploy"]
    P9 --> P10
```

---

## Phase 0 — Foundation & Scaffolding

**Goal:** A running, empty modular monolith with the package seams, build, local infra, and cross-cutting plumbing in place — so every later phase only adds domain logic.

**Deliverables (what is achieved)**
- Spring Boot app skeleton with build tooling (Gradle or Maven), pinned Java LTS version, dependency management.
- **Package structure created exactly per System Design §9** — one package per module, each a future extract seam:
  `api`, `security/user`, `security/device`, `mqtt`, `registry`, `telemetry`, `rules`, `command`, `alert`, `audit`, `health`, `common`.
- Spring profiles: `local`, `test`, `prod`. Externalized config; no secrets in source.
- **Docker Compose** for local dev: PostgreSQL, an MQTT broker (Mosquitto for dev), and Redis (off by default, profile-gated).
- Migration framework wired (Flyway or Liquibase) with an empty baseline migration that applies.
- **RFC 9457 error handling shell** — `@RestControllerAdvice` mapping validation/auth/conflict/not-found to Problem Details with `type`, `title`, `status`, `detail`, `instance`, `errors[]`. Stack traces never leaked.
- **Cross-cutting conventions wired once:** camelCase JSON (Jackson config), ISO-8601 UTC serialization, global `/api/v1` base path, pagination envelope types (`CursorPage`, `OffsetPage`) and `PagedResponse<T>`, `Idempotency-Key` handling infrastructure (24 h replay store).
- OpenAPI/Swagger generation enabled and served; CI skeleton (build + test) and code-style/formatting check.
- `common/` utilities: shared enums (`Role`, `UserStatus`, `DeviceCategory`, `DeviceStatus`, `CommandStatus`, `AlertStatus`, `Severity`, `ActorType`), validation helpers, time/partitioning helpers.

**Endpoints/Topics:** none yet (a liveness/readiness probe under `health/` infra is fine).
**Modules:** all packages created; `common`, `api` error layer populated.
**Load-bearing decisions to honor:** modular-monolith layout (§5.1, §9); one error shape (API §1); URI versioning `/v1/`.
**DoD:** app boots, empty migration applies, error handler returns Problem Details for a forced error, OpenAPI UI renders, CI green.
**Tests:** context-loads test; one Problem-Detail mapping test; JSON casing/date serialization test.

---

## Phase 1 — Persistence & Data Model

**Goal:** The complete schema from System Design §4, including time partitioning and retention, plus the cross-cutting **audit writer** that later phases depend on.

**Deliverables (what is achieved)**
- **Migrations for all 13 tables:** `users`, `refresh_tokens`, `devices`, `device_credentials`, `device_scopes`, `device_health`, `sensors`, `telemetry`, `sensor_latest`, `commands`, `rules`, `alerts`, `audit_logs`.
- **Range partitioning by month** for `telemetry` and `audit_logs`; automated partition creation (pg_partman or a scheduled job in `common`).
- **Retention job** that drops old partitions (metadata op, not `DELETE`) — wired but driven by config (horizon TBD per Open Question #1; default conservative).
- **Indexes that matter:** `telemetry (sensor_id, ts DESC)` and `(zone, ts DESC)`; appropriate unique/lookup indexes on `users.username`, `device_credentials.client_id`, FKs. Deliberately **no FK from `telemetry` to `devices`** (append-only fact log — §4).
- JPA entities + repositories per module, **respecting write-ownership** (only owning module writes its tables).
- **`sensor_latest`** and **`device_health`** modeled as single-row-per-key upsert targets (current state), separate from history.
- **Audit module writer:** an internal `AuditService` append API (actor, actorType, event, target, detail JSON, ip) writing to partitioned `audit_logs`. Available to all modules from here on. (Query API comes in Phase 9.)
- Seed/dev-fixture loader for `local` (a few zones, devices, one admin user) to make later phases testable.

**Endpoints/Topics:** none.
**Modules:** all repositories; `audit` (writer half).
**Data:** entire ER model (§4).
**Load-bearing decisions to honor:** Postgres-for-everything + monthly partitioning + drop-don't-delete retention (§5.2); current-state vs history split (§5.3, §4); telemetry has no device FK; refresh tokens stored hashed server-side; one-row-per-device health (not per-heartbeat).
**DoD:** migrations apply; partitions auto-create for current + next month; retention job dry-run logs the drop set; entities round-trip in repository tests; audit writer persists an entry.
**Tests (Testcontainers Postgres):** migration apply test; partition routing test (insert into `telemetry` lands in correct partition); index presence assertions; audit-writer integration test.

---

## Phase 2 — Security & Identity

**Goal:** Working authentication, RBAC, device tokens, rate limiting, and the user-admin CRUD surface. After this, every later endpoint can be gated correctly.

**Deliverables (what is achieved)**
- **Spring Security + OAuth2 Resource Server** validating JWTs; method-level `@PreAuthorize` with role hierarchy `SUPER_ADMIN > ADMIN > OPERATOR > VIEWER`.
- **User auth flow:**
  - `POST /v1/auth/login` → access (1 h) + refresh (30 d) tokens, `role` in response.
  - `POST /v1/auth/refresh` → **rotate** refresh token (revoke old, issue new), mint access; reuse of revoked token → `401` `errors/token-revoked`.
  - `POST /v1/auth/logout` → revoke presented refresh token (`204`).
  - Passwords hashed with **Argon2id** (BCrypt acceptable fallback); refresh tokens stored **hashed** in `refresh_tokens`, revocable.
  - Bad credentials → `401` (never `403`, never 200-with-error).
- **Device auth:** `POST /v1/oauth2/token` client-credentials grant; secret verified against hash; **granted scopes = intersection(stored, requested)**; scopes `telemetry:publish`, `heartbeat:publish`, `command:subscribe`, `command:ack`.
- **Rate limiting filter:** User 100/min, Device 300/min, Auth 20/min, telemetry configurable; `429` + `Retry-After` + `RateLimit-*` headers. Counters in-memory now, **Redis-backed switch** ready (profile-gated) for multi-instance.
- **Security headers** on all responses: HSTS, `X-Content-Type-Options`, `X-Frame-Options`, CSP. TLS/HTTPS enforced in `prod` config (plain HTTP disabled).
- **Users & RBAC admin (API §3):** `GET/POST /v1/users`, `GET/PATCH/DELETE /v1/users/{id}`, `POST /v1/users/{id}/password-reset`.
  - `SUPER_ADMIN` required to grant `ADMIN`/`SUPER_ADMIN`; `ADMIN` manages `OPERATOR`/`VIEWER`; over-authority grant → `403`.
  - Duplicate username → `409`; soft-delete sets `DISABLED` + revokes refresh tokens; **no `passwordHash` in DTO**.
- Audit entries for login, role/status change, password reset, user delete.

**Endpoints:** `/auth/login`, `/auth/refresh`, `/auth/logout`, `/oauth2/token`, `/users`, `/users/{userId}`, `/users/{userId}/password-reset`.
**Modules:** `security/user`, `security/device`, `api` (auth + user controllers), `audit` (consumed).
**Load-bearing decisions to honor:** refresh-token server-side revocation (§7); Argon2id; scope intersection; RBAC in JWT; rate-limit at filter, Redis-ready (§7).
**DoD:** a user can log in, refresh (with rotation), and log out; revoked refresh token is rejected; a device can mint a scoped token; RBAC denies under-privileged calls with `403`; rate limit returns `429`; user CRUD honors authority rules and audits changes.
**Tests:** login/refresh/rotation/reuse-detection; Argon2id verify; scope-intersection; RBAC matrix (`403` cases); rate-limit `429`; user CRUD authority + `409` duplicate; security-header presence.

---

## Phase 2.5 — Token Revocation & Denylist

**Goal:** Make revocation of access *and* refresh tokens **instantaneous and enforceable**, closing two gaps the Phase 2 auth flow leaves open: a stateless 1 h access token cannot be killed before it expires, and the `refresh_tokens.revoked` flag alone costs a DB round-trip on every refresh. This phase finalizes the Security & Identity layer (System Design §7 "Token revocation (denylist)") before the registry, ingest, and command paths build on it. *(Added after the §7 security update; slots in right after the now-complete Phase 2.)*

**Deliverables (what is achieved)**
- **Random `jti` on every access token** — minted in the Phase 2 token service so each issued JWT is individually addressable for revocation.
- **`refresh_tokens.rotated_to` column (migration)** — self-referential pointer to the token a refresh was rotated into, enabling the reuse cascade to walk the chain. *(System Design §7 references `rotated_to`; the §4 ER diagram still shows only `revoked` — this migration reconciles them.)*
- **`TokenDenylist` SPI with two interchangeable backends (§7):**
  - `InMemoryTokenDenylist` (default, `iot.redis.enabled=false`) for single-instance and tests.
  - `RedisTokenDenylist` (`iot.redis.enabled=true`) so every instance sees the same denials when running >1 instance. Identical interface; switching is a config flip.
  - **TTL = remaining lifetime of the underlying token** — entries auto-expire when the token would have anyway, so the store self-prunes and never outlives what it blocks.
- **One validator gates every JWT:** a custom `OAuth2TokenValidator<Jwt>` chained into the `NimbusJwtDecoder`, so **both user and device** tokens pass through it. A token whose `jti` is denylisted fails verification **ahead of** issuer/expiry checks.
- **Two key spaces, two purposes (§7):**
  - **Access `jti`** — blocks an issued JWT before its 1 h natural expiry; added on logout (when the client presents its access token) and on demand for forced sign-out.
  - **Refresh hash** (SHA-256 of the raw token) — short-circuits the refresh path before any DB lookup and serves as the fast-deny entry for any revoked/rotated token.
- **Refresh-reuse cascade (§7):** presenting a revoked/rotated refresh token (likely compromise) walks `rotated_to` and denylists **every descendant's hash**, returns `401` `errors/token-revoked`, and emits a **detection signal** (consumed in Phase 10).
- **Logout & refresh upgraded:** logout now additionally denylists the presented access `jti` **and** the refresh hash (truly instantaneous, not "eventual within 1 h"); refresh checks the denylist before the DB. The DB `revoked` flag remains authoritative; the denylist is the fast-deny layer in front of it.
- Audit on forced revocation and reuse-cascade trigger.

**Endpoints:** no new REST operations — augments the existing `/auth/login` (adds `jti`), `/auth/refresh` (denylist + cascade), `/auth/logout` (denylist access + refresh), and the JWT validation path for **every** authenticated endpoint.
**Modules:** `security/user`, `security/device` (shared validator path), `common` (denylist SPI), `audit` (consumed).
**Load-bearing decisions to honor:** denylist as a fast-deny layer in front of the authoritative DB `revoked` flag; one validator for user *and* device JWTs; TTL = remaining token lifetime; reuse cascade via `rotated_to`; pluggable in-memory/Redis backend switched by config (§7).
**DoD:** a logged-out access token is rejected immediately (not after 1 h); a denylisted `jti` fails JWT validation for both user and device tokens; presenting a rotated-out refresh token triggers the cascade, revokes all descendants, and returns `401` `errors/token-revoked`; flipping `iot.redis.enabled` swaps backends with identical behavior; denylist entries expire with their underlying token.
**Tests:** access-token denylist on logout → immediate `401`; `jti` validator applies to user **and** device tokens; refresh-reuse cascade revokes the descendant chain; TTL expiry of entries; in-memory vs Redis backend parity (Testcontainers Redis); validator ordering (denylist before issuer/expiry).

---

## Phase 3 — Device Registry & Lifecycle

**Goal:** Full device administration — registry CRUD, lifecycle state machine, credentials (write-once secret), and scopes.

**Deliverables (what is achieved)**
- **Registry CRUD:** `GET /v1/devices` (offset paged; filters `zone`, `category`, `deviceType`, `status`), `POST /v1/devices` (`201` + `Location`), `GET /v1/devices/{deviceId}`, `PATCH /v1/devices/{deviceId}` (firmware/zone/type), `GET /v1/devices/{deviceId}/sensors`.
  - Duplicate `deviceId` → `409`; sensor without valid `parentGatewayId` → `422`.
  - `Idempotency-Key` supported on `POST /devices`.
- **Lifecycle as explicit named actions** (not free-form `PATCH status`):
  - `POST /v1/devices/{deviceId}:activate` (`INACTIVE/SUSPENDED → ACTIVE`).
  - `POST /v1/devices/{deviceId}:suspend` (`ACTIVE → SUSPENDED`, disables credentials).
  - `POST /v1/devices/{deviceId}:decommission` (`* → DECOMMISSIONED`, terminal; revokes credentials + topic ACLs).
  - Illegal transition → `409` `errors/invalid-lifecycle-transition`.
- **Credentials & scopes:**
  - `POST /v1/devices/{deviceId}/credentials` issue (secret shown **once**, `201`).
  - `GET .../credentials` metadata only (`clientId`, `rotatedAt`) — **never the secret**.
  - `POST .../credentials:rotate` — new secret once; **old secret valid for a grace window** (`previous_secret_hash`), `graceExpiresAt` returned.
  - `GET /v1/devices/{deviceId}/scopes`, `PUT .../scopes` (full replace, unambiguous set).
  - `Idempotency-Key` supported on credential issue/rotate.
- Audit on register, update, every lifecycle transition, credential issue/rotate, scope change. Decommission/suspend side-effects coordinate with `security/device`.

**Endpoints:** `/devices`, `/devices/{deviceId}`, `/devices/{deviceId}/sensors`, `:activate`, `:suspend`, `:decommission`, `/credentials`, `/credentials:rotate`, `/scopes`.
**Modules:** `registry`, `security/device` (credential/scope lifecycle), `api`, `audit`.
**Load-bearing decisions to honor:** named lifecycle actions with side effects (API §4); write-once secret + rotation grace window (§7); scopes via `PUT`; idempotency keys.
**DoD:** an admin can register a gateway, parent sensors to it, walk the full lifecycle (rejecting illegal jumps with `409`), issue/rotate credentials (secret returned once only, old valid during grace), and replace scopes — all audited.
**Tests:** lifecycle state-machine (legal + illegal transitions); secret-shown-once + grace-window validity; duplicate-device `409`; orphan-sensor `422`; scope replace; idempotent re-POST returns original result.

---

## Phase 4 — MQTT Adapter + Telemetry Ingest + Current State

**Goal:** The end-to-end ingest path. Stand up the MQTT client once (reused by Phases 5 & 6), funnel **both MQTT and HTTP** into one Telemetry Service, persist history + current state, and serve the dashboard hot path.

**Deliverables (what is achieved)**
- **MQTT Adapter (`mqtt`):** persistent-session subscriber (`cleanSession=false`) + publisher; MQTTS/TLS; topic↔handler mapping; reconnect with backoff; **Last Will & Testament** registration support for presence; per-device topic ACL alignment (per-gateway telemetry topic `iot/telemetry/{zone}/{gateway_id}` per §6).
- **One ingestion funnel (§5.4):** the MQTT telemetry handler and `POST /v1/telemetry` call the **same `TelemetryService`** — validation, persistence, state update, and rule hand-off live in exactly one place.
  - `POST /v1/telemetry` (device scope `telemetry:publish`): synchronous shape validation (`422` on bad shape), then **`202`**; persistence + rule hand-off async. Batch of readings; each item numeric **xor** boolean.
- **Ingest-time integrity controls (§7 IoT abuse cases):** stamp a **server-side received timestamp** and flag implausible device-`ts` skew (defeats **stale-replay** of an old "all clear"); **per-device ingest rate limit** plus a gap/anomaly-detection seam to surface **sensor flooding/blinding**; the backend **re-validates that payload `gatewayId`/`deviceId` equals the authenticated identity** — never trust the broker ACL alone (belt-and-suspenders for T1/T2).
- **Persistence:** append rows to the current `telemetry` partition; **upsert `sensor_latest`** per sensor.
- **Rule hand-off seam:** persist first, then enqueue a reading event to a bounded in-process queue (consumed in Phase 7). Non-blocking — the MQTT callback never waits on rules.
- **History query:** `GET /v1/telemetry` (cursor paged) — **exactly one of `sensorId` or `zone` required** + bounded time window; missing/oversized window → `422`. Maps to the `(sensor_id, ts DESC)`/`(zone, ts DESC)` indexes.
- **Current state hot path (API §6):** `GET /v1/current-state` (filter `zone`), `GET /v1/sensors/{sensorId}/latest`, `GET /v1/connectivity` (zone roll-up), served from `sensor_latest`/`device_health` — **never** the telemetry partitions. Eventually-consistent-by-one-sample; short `Cache-Control`.
  - *(`GET /v1/devices/{deviceId}/health` is delivered in Phase 5 with the health pipeline.)*

**Endpoints:** `POST /v1/telemetry`, `GET /v1/telemetry`, `GET /v1/current-state`, `GET /v1/sensors/{sensorId}/latest`, `GET /v1/connectivity`.
**Topics:** `iot/telemetry/{zone}/{gateway_id}` (subscribe), LWT `iot/status/{device_id}` plumbing.
**Modules:** `mqtt`, `telemetry`, `api`, `health` (read side for connectivity/sensor_latest).
**Load-bearing decisions to honor:** one funnel for both transports (§5.4); persist-before-evaluate + async rule hand-off (§5.6); current/history split (§5.3); persistent MQTT session (§8); mandatory bounded window on partitioned reads (API §5); `202` for ingest; **ingest-time integrity — server-side timestamp/stale-replay flag, per-device ingest rate limit, payload-identity re-validation (§7)**.
**DoD:** a reading published over MQTT and the same reading POSTed over HTTP both land in `telemetry` + update `sensor_latest`; the dashboard reads current state in the hot path without touching partitions; history query rejects unbounded windows with `422`; broker restart doesn't lose QoS-1 messages (persistent session); a reading whose payload identity ≠ authenticated identity is rejected, and an implausible-`ts` (stale-replay) reading is flagged.
**Tests (Testcontainers Postgres + MQTT broker):** MQTT→DB end-to-end; HTTP fallback→same service; numeric-xor-boolean validation; unbounded-window `422`; current-state from `sensor_latest`; reconnect/persistent-session redelivery; payload-identity-mismatch rejected; stale/implausible-`ts` flagged; per-device ingest rate limit trips.

---

## Phase 5 — Heartbeat, Health & Connectivity

**Goal:** Device liveness — heartbeat ingest over both transports, LWT-driven presence, and the health/connectivity read surface.

**Deliverables (what is achieved)**
- **Heartbeat ingest:** MQTT `iot/heartbeat/{device_id}` handler + `POST /v1/heartbeat` (device scope `heartbeat:publish`), both upserting the single `device_health` row (not a history table). Authenticated device identity **must match body `deviceId`** → mismatch `403`. Returns `202` (async upsert).
- **Presence via LWT:** broker last-will on `iot/status/{device_id}` flips `connection_status` to `OFFLINE` on ungraceful drop; heartbeat/telemetry flip it `ONLINE` and update `last_seen`. More reliable than waiting for a missed heartbeat (§6, §8).
- **Health read endpoint:** `GET /v1/devices/{deviceId}/health` (latest health + connectivity), completing the Phase-4 current-state surface.
- Optional staleness sweep: mark devices `OFFLINE` if `last_seen` exceeds a configurable threshold (defense-in-depth alongside LWT).

**Endpoints:** `POST /v1/heartbeat`, `GET /v1/devices/{deviceId}/health` (and `GET /v1/connectivity` finalized with live presence).
**Topics:** `iot/heartbeat/{device_id}` (subscribe), `iot/status/{device_id}` (LWT consume).
**Modules:** `health`, `mqtt`, `api`.
**Load-bearing decisions to honor:** one health row per device upserted (§4); LWT presence (§6); identity-matches-body for heartbeat (API §7); `202` async.
**DoD:** heartbeats over MQTT and HTTP both upsert `device_health`; a device dropping ungracefully shows `OFFLINE` via LWT; mismatched-identity heartbeat → `403`; connectivity roll-up reflects live state.
**Tests:** heartbeat upsert (both transports); identity-mismatch `403`; LWT offline transition; staleness sweep; connectivity roll-up.

---

## Phase 6 — Commands: Dispatch, Ack & Timeout Sweeper

**Goal:** The command path with full tracked lifecycle and at-least-once safety.

**Deliverables (what is achieved)**
- **Issue:** `POST /v1/commands` (`OPERATOR`, **`Idempotency-Key` required**) → persist `command` as `PENDING`, publish to `iot/command/{device_id}` (QoS 1), return **`202`** + `Location`. Targeting a non-actuator or `DECOMMISSIONED` device → `422`.
- **Ack correlation:** subscribe `iot/command_ack/{device_id}`; correlate by `commandId`; advance `PENDING → RECEIVED → SUCCESS/FAILED`, stamping `received_at`/`executed_at`.
- **Idempotent state-sets:** actions are `SET status=ON` style (not `TOGGLE`); document the device-side dedupe-on-`commandId` contract so QoS-1 redelivery is harmless (§5.5).
- **Timeout sweeper:** scheduled job marks `PENDING/RECEIVED` commands `TIMEOUT` after N seconds without ack (config-driven).
- **Command-suppression & fail-safe (§7 "Availability as a security property"):** a `TIMEOUT` is emitted as a **detection signal** (consumed in Phase 10) so an attacker dropping MQTT messages can't silently suppress `exhaust ON`; document the **fail-safe actuator default** contract — devices adopt a known safe state on comms loss rather than dropping a safety action.
- **Status reads:** `GET /v1/commands` (cursor paged; filters `targetId`, `status`, `from`, `to`), `GET /v1/commands/{commandId}`. **No cancel/delete** endpoint — issue the inverse state-set instead.
- **Internal issue interface:** a published `CommandService` interface so the rule engine (Phase 7) can issue commands without touching the controller or repository.
- Audit on command issue + execution.

**Endpoints:** `POST /v1/commands`, `GET /v1/commands`, `GET /v1/commands/{commandId}`.
**Topics:** `iot/command/{device_id}` (publish), `iot/command_ack/{device_id}` (subscribe).
**Modules:** `command`, `mqtt`, `api`, `audit`.
**Load-bearing decisions to honor:** QoS-1 + idempotent state-sets + dedupe-on-commandId (§5.5); timeout sweeper (§8); `202` + polling, **no cancel** (API §8); idempotency key required.
**DoD:** issuing a command persists `PENDING`, publishes over MQTT, and returns `202`; acks drive the lifecycle to `SUCCESS/FAILED`; missing ack lands on `TIMEOUT`; re-issuing with the same `Idempotency-Key` returns the original record; invalid target → `422`.
**Tests:** issue→publish→ack lifecycle; timeout sweep; idempotency replay; duplicate-delivery harmlessness; invalid-target `422`; cursor pagination + filters.

---

## Phase 7 — Rule Engine

**Goal:** Safe, async rule evaluation that turns telemetry into commands and alerts off the ingest hot path.

**Deliverables (what is achieved)**
- **Rule CRUD (API §9):** `GET /v1/rules` (offset paged; filter `enabled`), `POST /v1/rules` (`201`), `GET /v1/rules/{ruleId}`, `PUT` (full replace), `PATCH` (toggle `enabled`/change `priority`), `DELETE` (`204`).
- **Safe evaluator (§5.6):** `condition`/`action` parsed and **validated on write** against a restricted grammar — locked-down read-only SpEL context **or** a small purpose-built DSL. **Never `eval`.** Unknown state / disallowed syntax / parse failure → `422` with the offending token.
- **Async worker:** consumes the bounded in-process queue from Phase 4; evaluates matching enabled rules in priority order; dispatches via the **published `CommandService` interface** (Phase 6) and the **`AlertService` interface** (Phase 8). The MQTT callback never blocks on this.
- **Re-derivability:** because telemetry is persisted before evaluation, an in-flight queue loss on restart drops no facts (§8). Document that exactly-once firing would require a durable queue (deferred).

**Endpoints:** `/rules`, `/rules/{ruleId}` (GET/PUT/PATCH/DELETE).
**Modules:** `rules`, `command` (consumed), `alert` (consumed), `api`, `audit`.
**Load-bearing decisions to honor:** async, off hot path, bounded queue + worker (§5.6); **no `eval`**, restricted evaluator validated on write (§5.6, API §9); `rules → command/alert` via published interfaces (§9 boundary rule).
**DoD:** a smoke-detection rule (`office_1.smoke == true → command(exhaust ON); alert(SMOKE, CRITICAL)`) fires asynchronously on a matching reading; a malformed/unsafe rule is rejected at write time with `422`; rule changes are audited; ingestion throughput is unaffected by a slow rule.
**Tests:** evaluator allow/deny grammar (incl. attempted code execution rejected); end-to-end reading→rule→command issue; rule priority ordering; write-time `422` on bad condition; CRUD + toggle.

---

## Phase 8 — Alerts

**Goal:** Alert lifecycle driven by rules and operated from the dashboard.

**Deliverables (what is achieved)**
- **`AlertService` raise API** (consumed by the rule engine in Phase 7): create `OPEN` alerts with `type`, `severity`, `zone`, `sourceDeviceId`, `message`.
- **Read/list:** `GET /v1/alerts` (cursor paged; filters `status`, `zone`, `severity`, `from`, `to`), `GET /v1/alerts/{alertId}`.
- **Explicit transitions** (not a writable `status` field) so the audit trail captures who did what:
  - `POST /v1/alerts/{alertId}:acknowledge` (`OPEN → ACK`, `OPERATOR`).
  - `POST /v1/alerts/{alertId}:resolve` (`→ RESOLVED`, `OPERATOR`).
  - Acknowledging an already-resolved alert → `409`.
- Audit on acknowledge/resolve.

**Endpoints:** `/alerts`, `/alerts/{alertId}`, `:acknowledge`, `:resolve`.
**Modules:** `alert`, `api`, `audit`; consumed by `rules`.
**Load-bearing decisions to honor:** explicit transitions over writable status (API §10); cursor pagination + bounded time filter.
**DoD:** a fired rule raises an `OPEN` alert; an operator can acknowledge then resolve; illegal transition → `409`; transitions are audited; list supports the documented filters.
**Tests:** raise→acknowledge→resolve; illegal transition `409`; filter/pagination; audit on transition.

---

## Phase 9 — Audit Query API

**Goal:** Expose the append-only audit trail that every module has been writing since Phase 1.

**Deliverables (what is achieved)**
- `GET /v1/audit-logs` (`ADMIN`, cursor paged; filters `actor`, `actorType`, `event`, `target`, `from`, `to`) over the partitioned `audit_logs` table — **bounded time window required** like telemetry.
- Confirm there is **no create/update/delete** path — entries are written internally only. Verify each module's writes (login, register/delete, credential rotation, rule change, command execution, role change) are queryable and carry actor, actorType, event, target, detail, ip.

**Endpoints:** `GET /v1/audit-logs`.
**Modules:** `audit` (query half), `api`.
**Load-bearing decisions to honor:** append-only, read-only API (API §10); mandatory bounded window on the partitioned read.
**DoD:** audit query returns entries across all event types with working filters; unbounded window → `422`; no write endpoints exist.
**Tests:** query by each filter; bounded-window enforcement; coverage assertion that each audited action from earlier phases appears.

---

## Phase 10 — Hardening, Observability, Ops & Deployment

**Goal:** Make it production-shaped against the non-functional targets and the failure modes in §8.

**Deliverables (what is achieved)**
- **NFR validation:** load test to confirm tens-of-msgs/s ingest, current-state `< 300 ms`, typical history `< 1 s`, command end-to-end `~1–2 s`. Capture results.
- **Broker resilience:** production MQTTS config; **HA/clustered broker** (EMQX/HiveMQ) or fast-restart + persistent sessions; documented HTTP-fallback degraded path; reconnect/backoff verified (§8 SPOF mitigation).
- **Multi-instance readiness:** flip rate-limit counters to **Redis**; document the MQTT-consumer-is-stateful catch and the chosen approach (**MQTT 5 shared subscriptions** or single **leader-elected** ingestion instance while REST scales) — per scaling-ladder step 5.
- **Partitioning + retention automation:** scheduled partition pre-creation and retention drop running in `prod`; alerting if a partition is missing.
- **Broker authorization:** per-device topic ACLs mapped from device identity (`device_id`/`gateway_id`), tied to credentials/scopes; verify a device cannot publish/subscribe another's topics (§7).
- **Observability:** structured logging, metrics (ingest rate, queue depth, command timeouts, partition size), liveness/readiness probes, dashboards/alerts.
- **Detection & incident response (§7):** alerting on repeated auth failures / credential stuffing, **refresh-token reuse-cascade triggered** (likely theft — signal from Phase 2.5), **broker ACL denials** (device publishing outside its topics → likely T1), commands from an **unexpected actor/target**, **telemetry gap/anomaly on a safety sensor**, and `403`/`429` spikes. **Device-compromise containment runbook:** suspend (`:suspend`) → decommission (`:decommission`) if confirmed → audit-review everything that identity did → cross-check neighbouring sensors for the compromise window (blast radius = one device, never the fleet).
- **Security review:** TLS 1.2+ everywhere, headers, secret handling (write-once, hashed), brute-force limits, dependency scan; confirm no internal IDs/hashes leak on any DTO. Gate against the **§7 build-time security checklist** and confirm the **standards mapping** (OWASP API Security Top 10 + OWASP IoT Top 10) is satisfied.
- **Deployment:** containerized build, prod profile, config/secret management, DB backup/restore + partition-aware retention runbook, broker runbook, rollback procedure.
- **Docs:** finalized OpenAPI, deprecation/versioning policy (`Deprecation`/`Sunset` headers), operational runbooks.

**Modules:** all (cross-cutting); `security`, `mqtt`, `common`, ops/deploy.
**Load-bearing decisions to honor:** broker as #1 SPOF mitigation; persistent-session/no-loss-on-reconnect; partition+retention automation; Redis-backed global limits; broker-side per-device ACLs (§7, §8); **detection & incident response + fail-safe-not-fail-open (§7)**.
**DoD:** NFR targets met under load; broker restart loses no QoS-1 data; retention/partition jobs run unattended in prod; per-device ACLs enforced; no sensitive field leaks; **detection alerts fire on the §7 signals (auth-failure burst, reuse cascade, ACL denial, command anomaly, sensor gap, `403`/`429` spike); device-compromise runbook rehearsed**; §7 security checklist green; deploy + rollback rehearsed.
**Tests:** load/perf suite vs targets; chaos test (broker down → fallback + recovery); ACL negative tests; partition/retention job tests; security scan; **detection-signal tests (each §7 alert condition triggers)**; end-to-end smoke across all flows.

---

## Coverage Matrix — REST operations → phase

All 44 OpenAPI operations are accounted for.

| # | Operation (`operationId`) | Method & path | Phase |
|---|---|---|---|
| 1 | login | `POST /auth/login` | 2 |
| 2 | refresh | `POST /auth/refresh` | 2 |
| 3 | logout | `POST /auth/logout` | 2 |
| 4 | deviceToken | `POST /oauth2/token` | 2 |
| 5 | listUsers | `GET /users` | 2 |
| 6 | createUser | `POST /users` | 2 |
| 7 | getUser | `GET /users/{userId}` | 2 |
| 8 | updateUser | `PATCH /users/{userId}` | 2 |
| 9 | deleteUser | `DELETE /users/{userId}` | 2 |
| 10 | resetUserPassword | `POST /users/{userId}/password-reset` | 2 |
| 11 | listDevices | `GET /devices` | 3 |
| 12 | registerDevice | `POST /devices` | 3 |
| 13 | getDevice | `GET /devices/{deviceId}` | 3 |
| 14 | updateDevice | `PATCH /devices/{deviceId}` | 3 |
| 15 | listGatewaySensors | `GET /devices/{deviceId}/sensors` | 3 |
| 16 | activateDevice | `POST /devices/{deviceId}:activate` | 3 |
| 17 | suspendDevice | `POST /devices/{deviceId}:suspend` | 3 |
| 18 | decommissionDevice | `POST /devices/{deviceId}:decommission` | 3 |
| 19 | issueDeviceCredential | `POST /devices/{deviceId}/credentials` | 3 |
| 20 | getDeviceCredentialMetadata | `GET /devices/{deviceId}/credentials` | 3 |
| 21 | rotateDeviceCredential | `POST /devices/{deviceId}/credentials:rotate` | 3 |
| 22 | getDeviceScopes | `GET /devices/{deviceId}/scopes` | 3 |
| 23 | replaceDeviceScopes | `PUT /devices/{deviceId}/scopes` | 3 |
| 24 | ingestTelemetry | `POST /telemetry` | 4 |
| 25 | queryTelemetry | `GET /telemetry` | 4 |
| 26 | getCurrentState | `GET /current-state` | 4 |
| 27 | getSensorLatest | `GET /sensors/{sensorId}/latest` | 4 |
| 28 | getConnectivity | `GET /connectivity` | 4 (presence finalized in 5) |
| 29 | getDeviceHealth | `GET /devices/{deviceId}/health` | 5 |
| 30 | ingestHeartbeat | `POST /heartbeat` | 5 |
| 31 | issueCommand | `POST /commands` | 6 |
| 32 | listCommands | `GET /commands` | 6 |
| 33 | getCommand | `GET /commands/{commandId}` | 6 |
| 34 | listRules | `GET /rules` | 7 |
| 35 | createRule | `POST /rules` | 7 |
| 36 | getRule | `GET /rules/{ruleId}` | 7 |
| 37 | replaceRule | `PUT /rules/{ruleId}` | 7 |
| 38 | updateRule | `PATCH /rules/{ruleId}` | 7 |
| 39 | deleteRule | `DELETE /rules/{ruleId}` | 7 |
| 40 | listAlerts | `GET /alerts` | 8 |
| 41 | getAlert | `GET /alerts/{alertId}` | 8 |
| 42 | acknowledgeAlert | `POST /alerts/{alertId}:acknowledge` | 8 |
| 43 | resolveAlert | `POST /alerts/{alertId}:resolve` | 8 |
| 44 | queryAuditLogs | `GET /audit-logs` | 9 |

## Coverage Matrix — MQTT topics → phase

| Purpose | Topic | Phase |
|---|---|---|
| Telemetry | `iot/telemetry/{zone}/{gateway_id}` | 4 |
| Presence (LWT) | `iot/status/{device_id}` | 4 (plumb) / 5 (consume) |
| Heartbeat | `iot/heartbeat/{device_id}` | 5 |
| Command | `iot/command/{device_id}` | 6 |
| Command ack | `iot/command_ack/{device_id}` | 6 |

## Coverage Matrix — load-bearing decisions → phase

| Decision (source) | Phase(s) |
|---|---|
| Modular monolith, extract-ready seams (§5.1, §9) | 0 |
| Postgres + monthly partitioning + drop-don't-delete retention (§5.2) | 1, 10 |
| Current-state vs history split; telemetry no-FK; per-device health row (§4, §5.3) | 1, 4, 5 |
| Refresh-token server-side hashed + revocation; Argon2id (§7) | 2 |
| Token denylist: instant revocation (access `jti` + refresh-hash), one validator for user+device, TTL = remaining lifetime, pluggable in-memory/Redis backend (§7) | 2.5 |
| Refresh-reuse cascade via `rotated_to` → revoke descendants + detection signal (§7) | 2.5 |
| Device client-credentials + scope intersection (§7) | 2, 3 |
| Rate limiting at filter, Redis-ready (§7) | 2, 10 |
| Named lifecycle actions with side effects (API §4) | 3 |
| Write-once secret + rotation grace window (§7) | 3 |
| One ingestion funnel for MQTT + HTTP (§5.4) | 4 |
| Persistent MQTT session, no loss on reconnect (§8) | 4, 10 |
| Mandatory bounded window on partitioned reads (API §5, §10) | 4, 9 |
| Command QoS-1 + idempotent state-sets + dedupe + timeout sweeper (§5.5, §8) | 6 |
| `202` + polling, no cancel endpoint (API §8) | 6 |
| Rule engine async off hot path; safe evaluator, no `eval` (§5.6) | 7 |
| `rules → command/alert` via published interfaces (§9) | 6, 7, 8 |
| Explicit alert transitions over writable status (API §10) | 8 |
| Append-only audit, read-only API (§7, API §10) | 1 (writer), 9 (query) |
| Broker SPOF mitigation / HA / shared-subscriptions scaling (§8) | 10 |
| Per-device broker topic ACLs (§7) | 4 (topic shape), 10 (enforcement) |
| Ingest-time integrity: stale-replay timestamp check, per-device ingest rate limit, payload-identity re-validation (§7 abuse cases) | 4 |
| Command-suppression detection + fail-safe actuator defaults (§7) | 6, 10 |
| Detection & incident response; device-compromise containment runbook; OWASP API/IoT mapping; security-checklist gate (§7) | 10 |
| RFC 9457 errors, camelCase, URI versioning, idempotency keys (API §1) | 0 |

---

## Deferred / explicitly out of scope (track, don't build now)

Per the design docs' evolution notes and ⚠️ assumptions — add only when an assumption flips:

- **Multi-tenant `tenantId`** on core tables + DTOs/filter (assumption #1). *Cheap to add now if multi-building is even plausible — decide before Phase 1.*
- **WebSocket/SSE push** (`GET /v1/stream/state`) for sub-second liveness (assumption #4) — additive to polling.
- **TimescaleDB hypertables + continuous aggregates** and `GET /v1/telemetry/aggregates` (assumption #3) — drop-in Postgres extension when charts dominate.
- **Bulk admin ops** (`POST /v1/devices:batch-suspend`), **notification channels/hooks**, **durable rule queue (Kafka/Redis Streams)**, **service extraction** of `telemetry`+`rules`.

## Open questions to resolve before Phase 1 (System Design §11)

1. **Retention horizon** for telemetry — sets partition/retention config and whether TimescaleDB is on the roadmap.
2. **Single vs multi-building** — if multi-tenant is ever possible, add `tenantId` now (near-free; painful to retrofit).
3. **Dashboard liveness** — polling assumed; confirm before treating push as out of scope.
4. **Broker product & HA** — Mosquitto (dev/simple) vs EMQX/HiveMQ (clustering, MQTT 5 shared subscriptions, richer ACLs); gates Phase 10 scaling choices.
