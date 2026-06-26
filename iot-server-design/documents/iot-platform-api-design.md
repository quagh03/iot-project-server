# Office IoT Monitoring & Control — REST API Design

**Status:** Design baseline · **Style:** REST/HTTPS (edge) · OAuth2 + JWT · RFC 9457 errors
**Companion documents:** *IoT Office Monitoring & Control — System Design* (architecture, data model, modules) and the *Data Specification* (MQTT topics, payloads, rate limits). This document designs the **REST edge** the system design leaves open (its §10 maps "§18–27 REST APIs" to the `api` module). The MQTT telemetry/command/heartbeat path is an existing async contract and is **referenced, not restated**, here.

---

## 0. Scope & consumers

The REST surface is designed from its three consumers inward, not from the database outward. No persistence detail (`password_hash`, `client_secret_hash`, raw partition rows, the `telemetry.id` bigint) ever appears in a wire shape.

| Consumer | Auth | What it needs |
|---|---|---|
| **Dashboard** (operators, low-tens concurrent) | User JWT (`VIEWER`+) | Current state per zone/sensor (< 300 ms), history over a range (< 1 s), device online/offline, alerts, command status |
| **Admin** (super-admin/admin) | User JWT (`ADMIN`+) | Device registry & lifecycle, credentials, users/RBAC, rules, audit |
| **Devices** (HTTP fallback only — MQTT is primary) | Device token (client-credentials + scopes) | `POST` telemetry, `POST` heartbeat when the broker is unreachable |

**Why REST here.** The edge is CRUD over resources with broad client compatibility and cacheable reads — REST's sweet spot. The high-volume, push-oriented device path stays on MQTT (efficient push, LWT presence, pub/sub fan-out); HTTP is only its degraded fallback. The trade-off accepted: the dashboard polls for "near-real-time" rather than receiving server push — adequate per assumption #4, and §11 below gives the upgrade path if push becomes a hard requirement.

---

## 1. Cross-cutting conventions

These apply to **every** endpoint so clients write one parser, one paginator, one error handler.

### Base & versioning
- Base path: `https://{host}/api/v1`
- **URI versioning** (`/v1/`) — most visible, easiest to route and test. Additive changes (new optional fields, new endpoints, new query params) ship within `v1`; only removals/renames/type-changes/tightened validation warrant `v2`.
- Deprecations announced with `Deprecation: true` + `Sunset: <date>` response headers and a migration window of months.

### Auth & authorization
- `Authorization: Bearer <access_token>` on everything except `POST /v1/auth/login`, `POST /v1/auth/refresh`, and the device token endpoint.
- **Users:** stateless JWT, 1 h access token; roles `SUPER_ADMIN > ADMIN > OPERATOR > VIEWER` carried in the token and enforced per-endpoint.
- **Devices:** OAuth2 client-credentials token gated by scopes (`telemetry:publish`, `heartbeat:publish`); devices may call **only** the ingest fallback endpoints.

### Casing, dates, IDs
- JSON is **camelCase** throughout.
- Timestamps are **ISO-8601 UTC** (`2026-06-25T10:30:00Z`).
- IDs are opaque strings on the wire (`deviceId`, `commandId`, `ruleId`). Never expose internal row PKs.

### Pagination
Two patterns, both **bounded** (`pageSize` max 200, default 50):

- **Cursor-based** for large/append-only or time-ordered sets — `telemetry`, `commands`, `alerts`, `audit-logs`. Opaque `cursor`; stable under concurrent writes.
- **Offset-based** for small, stable admin sets — `devices`, `users`, `rules`, `sensors`.

Collections use an **envelope** carrying pagination metadata:

```json
{
  "data": [ /* items */ ],
  "page": { "nextCursor": "b3Jk...", "hasMore": true, "pageSize": 50 }
}
```

Offset collections instead carry `{ "page": { "offset": 0, "limit": 50, "total": 142 } }`.

### Filtering & sorting
Query params: `?zone=office_1&status=ACTIVE&sort=-createdAt`. A leading `-` means descending. Time ranges use `from`/`to` (inclusive/exclusive respectively), ISO-8601.

### Idempotency
`POST`s that create or trigger side effects accept an **`Idempotency-Key`** header (client-generated UUID). The server returns the original result on retry within a 24 h window. Required in practice for `POST /commands`; supported for `POST /devices` and credential issue/rotate.

### Errors — RFC 9457 Problem Details
One shape everywhere. `type` (stable, machine-readable) and `status` drive client branching; `detail` is human-facing and may change. Validation lists **every** failing field. Stack traces and internal messages are never leaked.

```json
{
  "type": "https://api.iot.example.com/errors/validation",
  "title": "Validation failed",
  "status": 422,
  "detail": "zone must be one of the registered zones",
  "instance": "/api/v1/devices",
  "errors": [
    { "field": "zone", "message": "unknown zone 'office_99'" }
  ]
}
```

### Status codes used
`200` read/update with body · `201` created (+ `Location`) · `202` async accepted (telemetry, heartbeat, command issue) · `204` no body (lifecycle transitions, deletes) · `400` malformed · `401` unauthenticated · `403` role/scope denied · `404` missing · `409` state conflict (duplicate id, lifecycle/version conflict) · `422` semantically invalid · `429` rate limited (+ `Retry-After`) · `500/503` server / broker-dependency fault. Never `200` with an error body.

### Rate limiting
Per the data spec, enforced at the API filter (Redis-backed counters when multi-instance): **User 100/min, Device 300/min, Auth 20/min**, telemetry-ingest configurable. Over-limit → `429` with `Retry-After` and `RateLimit-*` headers.

---

## 2. Authentication

### Consumer & use case
Operators sign in for a JWT; devices exchange client credentials for a scoped token when falling back to HTTP.

### Contract

| Method & path | Purpose | Auth | Success |
|---|---|---|---|
| `POST /v1/auth/login` | User sign-in → access + refresh tokens | none | `200` |
| `POST /v1/auth/refresh` | Rotate refresh token, mint new access token | refresh token | `200` |
| `POST /v1/auth/logout` | Revoke the presented refresh token | refresh token | `204` |
| `POST /v1/oauth2/token` | Device client-credentials grant (`grant_type=client_credentials`) | client_id/secret | `200` |

**`POST /v1/auth/login` request / response**
```json
// → request
{ "username": "ada", "password": "••••••••" }

// ← 200
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "refreshToken": "eyJ...",
  "role": "OPERATOR"
}
```

Refresh tokens are **rotated on use** (old one revoked, new one issued) and stored hashed server-side so they can be revoked before their 30-day expiry — a stateless 30-day token you cannot kill is a liability. Reuse of a revoked refresh token → `401` with type `.../errors/token-revoked`. Bad credentials → `401` (never `403`, never a 200-with-error). Auth endpoints are rate-limited to 20/min to blunt brute force.

The device token endpoint accepts standard OAuth2 form-encoded params and returns an access token whose granted scopes are the intersection of the device's stored scopes and any requested. Devices use this token **only** for `POST /v1/telemetry` and `POST /v1/heartbeat`.

---

## 3. Users & RBAC (admin)

### Consumer & use case
Admins manage operator accounts and roles. `SUPER_ADMIN` required to grant `ADMIN`/`SUPER_ADMIN`; `ADMIN` may manage `OPERATOR`/`VIEWER`.

### Contract

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `GET /v1/users` | List (offset paged; filter `?role=&status=`) | `ADMIN` | `200` |
| `POST /v1/users` | Create user | `ADMIN` | `201` |
| `GET /v1/users/{userId}` | Read | `ADMIN` | `200` |
| `PATCH /v1/users/{userId}` | Update role / status | `ADMIN` | `200` |
| `POST /v1/users/{userId}/password-reset` | Issue reset (no plaintext in/out) | `ADMIN` / self | `204` |
| `DELETE /v1/users/{userId}` | Soft-delete (sets status `DISABLED`, revokes refresh tokens) | `ADMIN` | `204` |

**User DTO** — note what is *absent*: no `passwordHash`.
```json
{
  "id": "usr_8f3a",
  "username": "ada",
  "role": "OPERATOR",
  "status": "ACTIVE",
  "createdAt": "2026-01-15T10:30:00Z"
}
```

Creating a username that already exists → `409`. Setting a role above the caller's own authority → `403`. Every role/status change writes an `audit_logs` entry.

---

## 4. Device registry & lifecycle (admin)

### Consumer & use case
Admins register gateways/sensors/actuators, edit metadata, drive the lifecycle (`ACTIVE | INACTIVE | SUSPENDED | DECOMMISSIONED`), and manage device credentials & scopes.

### Contract — registry

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `GET /v1/devices` | List (offset paged; filter `?zone=&category=&deviceType=&status=`) | `VIEWER` | `200` |
| `POST /v1/devices` | Register a device | `ADMIN` | `201` |
| `GET /v1/devices/{deviceId}` | Read | `VIEWER` | `200` |
| `PATCH /v1/devices/{deviceId}` | Update firmware/zone/type | `ADMIN` | `200` |
| `GET /v1/devices/{deviceId}/sensors` | Sensors parented by a gateway | `VIEWER` | `200` |

**Device DTO**
```json
{
  "deviceId": "gw_office1_01",
  "category": "gateway",
  "deviceType": "temp",
  "zone": "office_1",
  "parentGatewayId": null,
  "firmwareVersion": "1.4.2",
  "status": "ACTIVE",
  "protocols": ["mqtt", "http"],
  "createdAt": "2026-01-15T10:30:00Z"
}
```

Registering a duplicate `deviceId` → `409`. A sensor without a valid `parentGatewayId` → `422`.

### Contract — lifecycle transitions
Lifecycle changes have side effects (suspend disables credentials; decommission is irreversible and revokes credentials + topic ACLs), so they are **explicit named actions**, not a free-form `PATCH status`. This prevents illegal jumps and makes intent auditable.

| Method & path | Transition | Min role | Success |
|---|---|---|---|
| `POST /v1/devices/{deviceId}:activate` | `INACTIVE/SUSPENDED → ACTIVE` | `ADMIN` | `204` |
| `POST /v1/devices/{deviceId}:suspend` | `ACTIVE → SUSPENDED` | `ADMIN` | `204` |
| `POST /v1/devices/{deviceId}:decommission` | `* → DECOMMISSIONED` (terminal) | `ADMIN` | `204` |

An illegal transition (e.g. activating a decommissioned device) → `409` with type `.../errors/invalid-lifecycle-transition`.

### Contract — credentials & scopes
The client secret is returned **exactly once**, at issue or rotation, and never again — only metadata is readable afterward.

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `POST /v1/devices/{deviceId}/credentials` | Issue first credential | `ADMIN` | `201` |
| `GET /v1/devices/{deviceId}/credentials` | Metadata only (`clientId`, `rotatedAt`) — never the secret | `ADMIN` | `200` |
| `POST /v1/devices/{deviceId}/credentials:rotate` | Rotate; old secret stays valid for a grace window | `ADMIN` | `200` |
| `GET /v1/devices/{deviceId}/scopes` | List granted scopes | `ADMIN` | `200` |
| `PUT /v1/devices/{deviceId}/scopes` | Replace the full scope set | `ADMIN` | `200` |

**Issue / rotate response (secret shown once)**
```json
{
  "clientId": "cli_gw_office1_01",
  "clientSecret": "shown-once-rotate-or-reissue-if-lost",
  "rotatedAt": "2026-06-25T10:30:00Z",
  "graceExpiresAt": "2026-06-25T11:30:00Z"
}
```

Scopes use `PUT` (full replace) rather than `PATCH` so the granted set is unambiguous. Every issue/rotation/scope change is audited.

---

## 5. Telemetry — ingest (fallback) & history

### Consumer & use case
Devices `POST` telemetry **only when MQTT is unavailable** (same Telemetry Service funnel as the broker path). The dashboard reads history over a time range.

### Contract

| Method & path | Purpose | Auth | Success |
|---|---|---|---|
| `POST /v1/telemetry` | HTTP fallback ingest (batch of readings) | device scope `telemetry:publish` | `202` |
| `GET /v1/telemetry` | History query (cursor paged) | user `VIEWER`+ | `200` |

**`POST /v1/telemetry`** — accepted, not synchronously processed, mirroring the async ingest pipeline. Returns `202`; validation of payload shape is synchronous (`422` on bad shape), persistence + rule hand-off are async.
```json
{
  "gatewayId": "gw_office1_01",
  "zone": "office_1",
  "readings": [
    { "sensorId": "s_temp_1", "sensorType": "temp", "valueNum": 22.4, "unit": "C", "ts": "2026-06-25T10:30:00Z" },
    { "sensorId": "s_smoke_1", "sensorType": "smoke", "valueBool": false, "ts": "2026-06-25T10:30:00Z" }
  ]
}
```

**`GET /v1/telemetry`** — must be scoped to avoid scanning the partitioned table unbounded. Exactly one of `sensorId` or `zone` is **required**, plus a bounded time window; these map to the `(sensor_id, ts DESC)` / `(zone, ts DESC)` indexes.

```
GET /v1/telemetry?sensorId=s_temp_1&from=2026-06-24T00:00:00Z&to=2026-06-25T00:00:00Z&pageSize=200
```
Missing both `sensorId` and `zone`, or an unbounded/oversized window → `422` (protects the big table from accidental full scans). Each item carries `sensorId`, `sensorType`, `valueNum`/`valueBool`, `unit`, `ts`.

A reading is numeric **or** boolean: exactly one of `valueNum` / `valueBool` is present per item — documented so clients don't expect both.

---

## 6. Current state (dashboard hot path)

### Consumer & use case
"What is each zone reading **now**" and "is this device online" — the < 300 ms dashboard path. Served from `sensor_latest` / `device_health`, never from the telemetry partitions.

### Contract

| Method & path | Purpose | Auth | Success |
|---|---|---|---|
| `GET /v1/current-state` | Latest reading per sensor (filter `?zone=`) | `VIEWER` | `200` |
| `GET /v1/sensors/{sensorId}/latest` | Latest reading for one sensor | `VIEWER` | `200` |
| `GET /v1/devices/{deviceId}/health` | Latest health + connectivity | `VIEWER` | `200` |
| `GET /v1/connectivity` | Online/offline roll-up across devices (filter `?zone=`) | `VIEWER` | `200` |

**Current-state item**
```json
{
  "sensorId": "s_temp_1",
  "zone": "office_1",
  "sensorType": "temp",
  "valueNum": 22.4,
  "ts": "2026-06-25T10:29:58Z"
}
```

**Health DTO**
```json
{
  "deviceId": "gw_office1_01",
  "connectionStatus": "ONLINE",
  "lastSeen": "2026-06-25T10:29:50Z",
  "memoryUsagePct": 41,
  "cpuUsagePct": 12,
  "wifiRssi": -58,
  "updatedAt": "2026-06-25T10:29:50Z"
}
```

These reads are **eventually consistent by one sample** (the live view may lag the freshest reading) — acceptable per the consistency targets and documented so clients don't treat it as transactional. Responses are safe to send with a short `Cache-Control: max-age` for polling clients.

---

## 7. Heartbeat ingest (fallback)

### Consumer & use case
Device connectivity upsert when MQTT/LWT is unavailable; updates the single `device_health` row, not a history table.

| Method & path | Purpose | Auth | Success |
|---|---|---|---|
| `POST /v1/heartbeat` | Upsert latest health for the calling device | device scope `heartbeat:publish` | `202` |

```json
{ "deviceId": "gw_office1_01", "memoryUsagePct": 41, "cpuUsagePct": 12, "wifiRssi": -58 }
```
The authenticated device identity must match the body `deviceId` → mismatch is `403` (a device cannot report health for another). Returns `202`; the upsert is applied asynchronously.

---

## 8. Commands

### Consumer & use case
Operators (and the rule engine, internally) issue actuator commands; the dashboard polls status through the `PENDING → RECEIVED → SUCCESS/FAILED/TIMEOUT` lifecycle. **Acks arrive over MQTT** (`iot/command_ack/{device_id}`), not REST — the REST side only issues and reports.

### Contract

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `POST /v1/commands` | Issue a command (requires `Idempotency-Key`) | `OPERATOR` | `202` |
| `GET /v1/commands` | List (cursor paged; filter `?targetId=&status=&from=&to=`) | `VIEWER` | `200` |
| `GET /v1/commands/{commandId}` | Status / lifecycle of one command | `VIEWER` | `200` |

**Issue request / response**
```json
// → POST /v1/commands   (header: Idempotency-Key: 5e9c...)
{ "targetId": "act_exhaust_1", "type": "actuator", "action": "SET", "parameters": { "status": "ON" } }

// ← 202   Location: /api/v1/commands/cmd_7a21
{ "commandId": "cmd_7a21", "status": "PENDING", "issuedAt": "2026-06-25T10:30:00Z" }
```

Issue returns **`202`**, not `201`: the resource (the command record) exists, but the *effect* (actuator reacting) is asynchronous over MQTT. The dashboard then polls `GET /v1/commands/{commandId}`:

```json
{
  "commandId": "cmd_7a21",
  "targetId": "act_exhaust_1",
  "action": "SET",
  "parameters": { "status": "ON" },
  "status": "RECEIVED",
  "issuedBy": "usr_8f3a",
  "issuedAt": "2026-06-25T10:30:00Z",
  "receivedAt": "2026-06-25T10:30:01Z",
  "executedAt": null
}
```

Because MQTT QoS 1 is at-least-once, **commands are idempotent state-sets** (`SET status=ON`, not `TOGGLE`) and devices dedupe on `commandId` — so a redelivery is harmless. There is deliberately **no cancel/delete** endpoint: a command in flight cannot be recalled; issue the inverse state-set instead. Commands with no ack within the window are swept to `TIMEOUT` server-side; clients observe this purely through `status`. Targeting a non-actuator or a `DECOMMISSIONED` device → `422`.

---

## 9. Rules (admin)

### Consumer & use case
Admins manage rules evaluated asynchronously against telemetry/state to dispatch commands and raise alerts.

### Contract

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `GET /v1/rules` | List (offset paged; filter `?enabled=`) | `OPERATOR` | `200` |
| `POST /v1/rules` | Create a rule | `ADMIN` | `201` |
| `GET /v1/rules/{ruleId}` | Read | `OPERATOR` | `200` |
| `PUT /v1/rules/{ruleId}` | Full replace | `ADMIN` | `200` |
| `PATCH /v1/rules/{ruleId}` | Toggle `enabled` / change `priority` | `ADMIN` | `200` |
| `DELETE /v1/rules/{ruleId}` | Remove | `ADMIN` | `204` |

**Rule DTO**
```json
{
  "ruleId": "rule_smoke_exhaust",
  "name": "Smoke → exhaust ON + alert",
  "enabled": true,
  "condition": "office_1.smoke == true",
  "action": "command(act_exhaust_1, SET, {status: ON}); alert(SMOKE, CRITICAL)",
  "priority": 10,
  "createdBy": "usr_8f3a"
}
```

`condition`/`action` are validated **on write** against the restricted expression grammar (the safe SpEL/DSL evaluator — never `eval`). A condition that references unknown state, uses disallowed syntax, or fails to parse → `422` with the offending token, so bad rules are rejected at the door rather than failing silently at evaluation time. `PATCH` is offered for the common toggle/priority case; `PUT` for a full rewrite. Rule changes are audited.

---

## 10. Alerts & audit (dashboard / admin)

### Alerts

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `GET /v1/alerts` | List (cursor paged; filter `?status=&zone=&severity=&from=&to=`) | `VIEWER` | `200` |
| `GET /v1/alerts/{alertId}` | Read | `VIEWER` | `200` |
| `POST /v1/alerts/{alertId}:acknowledge` | `OPEN → ACK` | `OPERATOR` | `200` |
| `POST /v1/alerts/{alertId}:resolve` | `→ RESOLVED` | `OPERATOR` | `200` |

Alert status is driven by explicit transitions (acknowledge/resolve) rather than a writable `status` field, so the audit trail captures *who* acknowledged *what*. Acknowledging an already-resolved alert → `409`.

**Alert DTO**
```json
{
  "alertId": "alrt_4521",
  "type": "SMOKE",
  "severity": "CRITICAL",
  "zone": "office_1",
  "sourceDeviceId": "s_smoke_1",
  "message": "Smoke detected in office_1",
  "status": "OPEN",
  "createdAt": "2026-06-25T10:30:00Z"
}
```

### Audit logs (read-only)

| Method & path | Purpose | Min role | Success |
|---|---|---|---|
| `GET /v1/audit-logs` | Query (cursor paged; filter `?actor=&actorType=&event=&target=&from=&to=`) | `ADMIN` | `200` |

Audit is **append-only** — there is no create/update/delete endpoint; entries are written internally by each module (login, registration/deletion, credential rotation, rule change, command execution, role change). The query hits the partitioned `audit_logs` table and so requires a bounded time window like telemetry.

---

## 11. Spring implementation sketch

Idiomatic signatures only — the system design's module boundaries (`api/` controllers delegating to per-module service interfaces) are preserved; controllers never touch another module's repository.

```java
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

  @GetMapping
  @PreAuthorize("hasRole('VIEWER')")
  PagedResponse<DeviceDto> list(DeviceFilter filter, OffsetPage page) { ... }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<DeviceDto> register(@Valid @RequestBody RegisterDeviceRequest req,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key) { ... } // 201 + Location

  @PostMapping("/{deviceId}:suspend")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> suspend(@PathVariable String deviceId) { ... }     // 204

  @PostMapping("/{deviceId}/credentials:rotate")
  @PreAuthorize("hasRole('ADMIN')")
  CredentialSecretDto rotate(@PathVariable String deviceId) { ... }        // secret shown once
}

@RestController
@RequestMapping("/api/v1/commands")
class CommandController {

  @PostMapping
  @PreAuthorize("hasRole('OPERATOR')")
  ResponseEntity<CommandAck> issue(@Valid @RequestBody IssueCommandRequest req,
                                   @RequestHeader("Idempotency-Key") String key) { ... } // 202 + Location

  @GetMapping("/{commandId}")
  @PreAuthorize("hasRole('VIEWER')")
  CommandDto status(@PathVariable String commandId) { ... }
}

@RestController
@RequestMapping("/api/v1/telemetry")
class TelemetryIngestController {

  @PostMapping
  @PreAuthorize("hasAuthority('SCOPE_telemetry:publish')")
  ResponseEntity<Void> ingest(@Valid @RequestBody TelemetryBatch batch,
                              @AuthenticationPrincipal DeviceIdentity device) { ... }   // 202
}

// One shape for every error
@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    var pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
    pd.setType(URI.create("https://api.iot.example.com/errors/validation"));
    pd.setTitle("Validation failed");
    pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
        .map(f -> Map.of("field", f.getField(), "message", f.getDefaultMessage())).toList());
    return pd;
  }
}
```

---

## 12. Evolution notes

Drawn so the next likely change is additive, not breaking — tracking the system design's open questions (§11) and ⚠️ assumptions.

- **Multi-building / multi-tenant (assumption #1).** Add an optional `tenantId` field to DTOs and a `?tenantId=` filter *now* (nearly free), resolved from the JWT. Existing single-tenant clients ignore it; no `v2` needed when tenancy turns on.
- **Dashboard liveness (assumption #4).** Today's contract is poll-based on `/current-state`, `/connectivity`, and `GET /commands/{id}`. If sub-second push becomes a requirement, add a **WebSocket/SSE stream** (e.g. `GET /v1/stream/state`) alongside — purely additive, the polling endpoints remain.
- **History aggregation (assumption #3 / TimescaleDB path).** When charts dominate, add `GET /v1/telemetry/aggregates?metric=temp&zone=office_1&interval=1h&from=&to=` backed by continuous aggregates — a new endpoint, not a change to `GET /v1/telemetry`.
- **Bulk admin ops.** If operators need batch device actions, add `POST /v1/devices:batch-suspend` rather than overloading the single-resource transitions.
- **Notifications.** Alert notification hooks (email/webhook) become `POST /v1/notification-channels` + a rule action — additive to §9/§10.
- **Service extraction.** Because every controller already delegates through a module service interface, peeling `telemetry`+`rules` into their own service later changes the wiring behind these paths, not the paths themselves — the REST contract is stable across that refactor.

---

### Verdict
✅ **A REST edge that matches the architecture's grain.** It honours the system design's load-bearing decisions — the current-state/history split (§6 vs §5), command idempotency and the async ack lifecycle (`202` + polling, no cancel), the one-ingestion-funnel fallback (`POST /telemetry` → same service), write-once device secrets, safe-evaluator rule validation on write, and append-only audit. The conventions (one error shape, bounded pagination, URI versioning, idempotency keys, scope/role gates) are uniform so clients integrate once. The deliberate constraints worth noting to consumers are **mandatory time-window scoping on the partitioned reads** (telemetry, audit) and the **one-sample eventual consistency** of the live state path.
