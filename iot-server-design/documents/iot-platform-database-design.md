# Office IoT Monitoring & Control — Database Design

**Status:** Design baseline · **Engine:** PostgreSQL 15+ · **Derived from:** *System Design* (§4 data model, §5 trade-offs) and *REST API Design* / OpenAPI 1.0.0

This document turns the system design's data model and the API's wire contracts into a concrete, normalized PostgreSQL schema: tables, types, constraints, keys, indexes, partitioning, and the reasoning behind each non-obvious choice. Where the source documents and this schema differ, the differences are called out explicitly (see **Additions beyond the ER diagram** and **Key decisions**).

---

## 1. Domain model

The platform tracks eleven core "things" plus two cross-cutting concerns:

| Entity | Purpose | Volume / growth |
|---|---|---|
| **users** | Operator accounts with RBAC roles. | Tiny, slow-changing. |
| **refresh_tokens** | Server-side, hashed, rotating refresh tokens (revocable before 30-day expiry). | Low; pruned on expiry. |
| **devices** | Registry of gateways, sensors, actuators + lifecycle state. | Hundreds of rows. |
| **device_credentials** | One client-credentials secret (hashed) per device, with a rotation grace slot. | One row per device. |
| **device_scopes** | OAuth2 scopes granted to each device (many per device). | A few rows per device. |
| **sensors** | Measurement points parented by a gateway (telemetry attribution). | Hundreds of rows. |
| **device_health** | **Latest** health/connectivity per device — upserted, not appended. | One row per device. |
| **telemetry** | Append-only time-series readings. **The one table that grows.** | ~160 M rows/year. |
| **sensor_latest** | Latest reading per sensor — the dashboard hot path. | One row per sensor. |
| **actuator_state** 🆕 | Latest desired-vs-reported state per actuator — the **operator control** hot path. | One row per actuator. |
| **commands** | Actuator commands with `PENDING → … → SUCCESS/FAILED/TIMEOUT` lifecycle. | Low; a few/minute. |
| **rules** | Stored rule conditions/actions evaluated asynchronously. | Tens of rows. |
| **alerts** | Raised alerts with `OPEN → ACK → RESOLVED` transitions. | Low, event-driven. |
| **audit_logs** | Append-only security/control event log. | Grows, slower than telemetry. |
| **idempotency_keys** | Stores `POST` results for safe retry within 24 h. | Short-lived, TTL-pruned. |

> 🆕 **What changed in this revision** — the *Operator Device Control* update (system design §1, §4, §5.8, §7) adds the **`actuator_state`** table below and an **optional `user_zone_grants`** table (§9). No other table changes: manual commands reuse the existing `commands` lifecycle and `idempotency_keys`; the new `MANUAL_COMMAND` / `SAFETY_OVERRIDE` events are plain `audit_logs` rows (the `event` column is free-form `VARCHAR`, so no migration). Live deltas ship as **Flyway `V3` / `V4`** (§10).

The governing access patterns (from system design §4): **append telemetry fast**; read **latest value per sensor** and **device online/offline** cheaply; query **telemetry by sensor or zone over a time range**; transactional **registry / RBAC / command** updates; append-only **audit** and **idempotent retries**.

---

## 2. ER diagram

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "issues"
    DEVICES ||--o| DEVICE_CREDENTIALS : "authenticates with"
    DEVICES ||--o{ DEVICE_SCOPES : "granted"
    DEVICES ||--o| DEVICE_HEALTH : "latest health"
    DEVICES ||--o| ACTUATOR_STATE : "latest actuator state"
    DEVICES ||--o{ SENSORS : "parent gateway of"
    DEVICES ||--o{ DEVICES : "parent_gateway_id (self-ref)"
    DEVICES ||--o{ COMMANDS : "targets"
    COMMANDS ||--o| ACTUATOR_STATE : "last command of"
    USERS ||--o{ USER_ZONE_GRANTS : "granted zones (optional §9)"
    DEVICES ||--o{ ALERTS : "source"

    USERS {
        uuid id PK
        varchar username UK
        varchar password_hash "argon2id"
        varchar role "CHECK enum"
        varchar status "ACTIVE|DISABLED"
        int version
        timestamptz created_at
        timestamptz updated_at
    }
    REFRESH_TOKENS {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK
        timestamptz expires_at
        boolean revoked
        uuid rotated_to FK "reuse detection"
        timestamptz created_at
    }
    DEVICES {
        varchar device_id PK
        varchar category "gateway|sensor|actuator"
        varchar device_type
        varchar zone
        varchar parent_gateway_id FK "null unless sensor"
        varchar firmware_version
        varchar status "ACTIVE|INACTIVE|SUSPENDED|DECOMMISSIONED"
        text_array protocols
        int version
        timestamptz created_at
        timestamptz updated_at
    }
    DEVICE_CREDENTIALS {
        varchar device_id PK_FK
        varchar client_id UK
        varchar client_secret_hash
        varchar previous_secret_hash "rotation grace"
        timestamptz grace_expires_at
        timestamptz rotated_at
        timestamptz created_at
    }
    DEVICE_SCOPES {
        varchar device_id PK_FK
        varchar scope PK "CHECK enum"
    }
    SENSORS {
        varchar sensor_id PK
        varchar gateway_id FK
        varchar type
        varchar zone
        timestamptz created_at
    }
    DEVICE_HEALTH {
        varchar device_id PK_FK
        varchar connection_status "ONLINE|OFFLINE"
        timestamptz last_seen
        smallint memory_usage_pct
        smallint cpu_usage_pct
        smallint wifi_rssi
        timestamptz updated_at
    }
    TELEMETRY {
        bigint id PK "part of composite PK"
        timestamptz ts PK "partition key"
        varchar zone
        varchar gateway_id "no FK (deliberate)"
        varchar sensor_id "no FK (deliberate)"
        varchar sensor_type
        float8 value_num "XOR value_bool"
        boolean value_bool "XOR value_num"
        varchar unit
    }
    SENSOR_LATEST {
        varchar sensor_id PK
        varchar zone
        varchar sensor_type
        float8 value_num
        boolean value_bool
        varchar unit
        timestamptz ts
    }
    ACTUATOR_STATE {
        varchar device_id PK_FK
        varchar desired_state "last commanded: ON|OFF|..."
        varchar reported_state "last confirmed by device"
        jsonb attributes "setpoint, level, mode"
        varchar last_command_id FK "ON DELETE SET NULL"
        timestamptz commanded_at
        timestamptz updated_at
    }
    USER_ZONE_GRANTS {
        uuid user_id PK_FK "optional — see §9"
        varchar zone PK
        varchar granted_by
        timestamptz granted_at
    }
    COMMANDS {
        varchar command_id PK
        varchar target_id FK
        varchar type
        varchar action
        jsonb parameters
        varchar status "PENDING|RECEIVED|SUCCESS|FAILED|TIMEOUT"
        varchar issued_by "user id | rule id | system (polymorphic, no FK)"
        timestamptz issued_at
        timestamptz received_at
        timestamptz executed_at
    }
    RULES {
        uuid rule_id PK
        varchar name
        boolean enabled
        text condition
        text action
        int priority
        varchar created_by
        int version
        timestamptz created_at
        timestamptz updated_at
    }
    ALERTS {
        bigint id PK
        varchar type
        varchar severity "INFO|WARNING|CRITICAL"
        varchar zone
        varchar source_device_id FK
        text message
        varchar status "OPEN|ACK|RESOLVED"
        varchar acknowledged_by
        timestamptz acknowledged_at
        varchar resolved_by
        timestamptz resolved_at
        int version
        timestamptz created_at
    }
    AUDIT_LOGS {
        bigint id PK "part of composite PK"
        timestamptz ts PK "partition key"
        varchar actor
        varchar actor_type "USER|DEVICE|SYSTEM"
        varchar event
        varchar target
        jsonb detail
        inet ip
    }
    IDEMPOTENCY_KEYS {
        uuid idempotency_key PK
        varchar endpoint PK
        varchar request_hash
        smallint response_status
        jsonb response_body
        timestamptz expires_at
        timestamptz created_at
    }
```

> `telemetry`, `sensor_latest`, `audit_logs`, and `idempotency_keys` are intentionally **not** linked by foreign keys to the registry — see **Key decisions §5.1**. The diagram omits those edges to reflect the real referential design.

---

## 3. Tables (DDL)

Target: PostgreSQL 15+. Conventions used throughout:

- "Enum" columns are `VARCHAR` + `CHECK` (portable and migratable; native PG `ENUM` is painful to alter — system design treats these loosely as enums, we pin them with CHECKs).
- All timestamps are `TIMESTAMPTZ` storing UTC.
- Surrogate keys are `BIGINT GENERATED ALWAYS AS IDENTITY` or `UUID` per the source model; device/command/sensor IDs are opaque strings supplied by the registry (and are what the API exposes).
- `gen_random_uuid()` requires the `pgcrypto` extension (built in on PG 13+ via `pgcrypto`/`uuid-ossp`).

```sql
-- =====================================================================
-- Extensions
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS pgcrypto;   -- gen_random_uuid()

-- =====================================================================
-- AUTH / SECURITY
-- =====================================================================

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,                  -- argon2id encoded string
    role          VARCHAR(16)  NOT NULL                    -- TECHNICIAN added in V2 (between OPERATOR and VIEWER)
                    CHECK (role IN ('SUPER_ADMIN','ADMIN','OPERATOR','TECHNICIAN','VIEWER')),
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','DISABLED')),  -- DISABLED = soft delete
    version       INTEGER      NOT NULL DEFAULT 0,        -- optimistic lock
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username)
);

CREATE TABLE refresh_tokens (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL,                     -- store hash only, never raw
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    rotated_to UUID         NULL REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
-- Find live tokens cheaply; prune expired/revoked ones in a job.
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens (expires_at)
    WHERE revoked = FALSE;

-- =====================================================================
-- DEVICE REGISTRY
-- =====================================================================

CREATE TABLE devices (
    device_id         VARCHAR(64) PRIMARY KEY,
    category          VARCHAR(16) NOT NULL
                        CHECK (category IN ('gateway','sensor','actuator')),
    device_type       VARCHAR(32) NOT NULL,   -- temp|hmid|smoke|light|ac|exhst_fan|curtain
    zone              VARCHAR(64) NOT NULL,
    parent_gateway_id VARCHAR(64) NULL
                        REFERENCES devices(device_id) ON DELETE RESTRICT,
    firmware_version  VARCHAR(32) NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'INACTIVE'
                        CHECK (status IN ('ACTIVE','INACTIVE','SUSPENDED','DECOMMISSIONED')),
    protocols         TEXT[]      NOT NULL DEFAULT '{}',
    version           INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Only sensors have a parent gateway; everything else must not (API: 422 otherwise).
    CONSTRAINT chk_devices_parent_gateway CHECK (
        (category = 'sensor'  AND parent_gateway_id IS NOT NULL) OR
        (category <> 'sensor' AND parent_gateway_id IS NULL)
    )
);
CREATE INDEX idx_devices_zone      ON devices (zone);
CREATE INDEX idx_devices_category  ON devices (category);
CREATE INDEX idx_devices_status    ON devices (status);
CREATE INDEX idx_devices_parent    ON devices (parent_gateway_id);

CREATE TABLE device_credentials (
    device_id            VARCHAR(64)  PRIMARY KEY
                           REFERENCES devices(device_id) ON DELETE CASCADE,
    client_id            VARCHAR(64)  NOT NULL,
    client_secret_hash   VARCHAR(255) NOT NULL,           -- hashed; never returned after issue
    previous_secret_hash VARCHAR(255) NULL,               -- valid during rotation grace window
    grace_expires_at     TIMESTAMPTZ  NULL,               -- when previous_secret_hash stops working
    rotated_at           TIMESTAMPTZ  NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_credentials_client_id UNIQUE (client_id)
);

CREATE TABLE device_scopes (
    device_id VARCHAR(64) NOT NULL
                REFERENCES devices(device_id) ON DELETE CASCADE,
    scope     VARCHAR(32) NOT NULL
                CHECK (scope IN ('telemetry:publish','command:subscribe',
                                 'command:ack','heartbeat:publish')),
    PRIMARY KEY (device_id, scope)
);

CREATE TABLE sensors (
    sensor_id  VARCHAR(64) PRIMARY KEY,
    gateway_id VARCHAR(64) NOT NULL
                 REFERENCES devices(device_id) ON DELETE RESTRICT,
    type       VARCHAR(32) NOT NULL,
    zone       VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sensors_gateway ON sensors (gateway_id);
CREATE INDEX idx_sensors_zone    ON sensors (zone);

-- =====================================================================
-- TELEMETRY (history) — range-partitioned by month on ts, NO FK to devices
-- =====================================================================

CREATE TABLE telemetry (
    id          BIGINT           GENERATED ALWAYS AS IDENTITY,
    ts          TIMESTAMPTZ      NOT NULL,
    zone        VARCHAR(64)      NOT NULL,
    gateway_id  VARCHAR(64)      NOT NULL,
    sensor_id   VARCHAR(64)      NOT NULL,
    sensor_type VARCHAR(32)      NOT NULL,
    value_num   DOUBLE PRECISION NULL,
    value_bool  BOOLEAN          NULL,
    unit        VARCHAR(16)      NULL,
    -- Partition key must be part of the PK on a partitioned table.
    PRIMARY KEY (id, ts),
    -- A reading is numeric XOR boolean — exactly one is present.
    CONSTRAINT chk_telemetry_value CHECK (
        (value_num IS NOT NULL) <> (value_bool IS NOT NULL)
    )
) PARTITION BY RANGE (ts);

-- The two query shapes the dashboard issues (defined on the parent, cascades to partitions).
CREATE INDEX idx_telemetry_sensor_ts ON telemetry (sensor_id, ts DESC);
CREATE INDEX idx_telemetry_zone_ts   ON telemetry (zone, ts DESC);

-- Example monthly partitions (automate creation with pg_partman or a scheduled job).
CREATE TABLE telemetry_2026_06 PARTITION OF telemetry
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE telemetry_2026_07 PARTITION OF telemetry
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- =====================================================================
-- CURRENT STATE (dashboard hot path) — one row per sensor / device, upserted
-- =====================================================================

CREATE TABLE sensor_latest (
    sensor_id   VARCHAR(64)      PRIMARY KEY,
    zone        VARCHAR(64)      NOT NULL,
    sensor_type VARCHAR(32)      NOT NULL,
    value_num   DOUBLE PRECISION NULL,
    value_bool  BOOLEAN          NULL,
    unit        VARCHAR(16)      NULL,
    ts          TIMESTAMPTZ      NOT NULL,
    CONSTRAINT chk_sensor_latest_value CHECK (
        (value_num IS NOT NULL) <> (value_bool IS NOT NULL)
    )
);
CREATE INDEX idx_sensor_latest_zone ON sensor_latest (zone);
-- Upsert on ingest:
--   INSERT INTO sensor_latest (...) VALUES (...)
--   ON CONFLICT (sensor_id) DO UPDATE SET
--       value_num = EXCLUDED.value_num, value_bool = EXCLUDED.value_bool,
--       unit = EXCLUDED.unit, ts = EXCLUDED.ts, zone = EXCLUDED.zone,
--       sensor_type = EXCLUDED.sensor_type
--   WHERE EXCLUDED.ts >= sensor_latest.ts;   -- guard against out-of-order samples

CREATE TABLE device_health (
    device_id         VARCHAR(64) PRIMARY KEY
                        REFERENCES devices(device_id) ON DELETE CASCADE,
    connection_status VARCHAR(8)  NOT NULL DEFAULT 'OFFLINE'
                        CHECK (connection_status IN ('ONLINE','OFFLINE')),
    last_seen         TIMESTAMPTZ NULL,
    memory_usage_pct  SMALLINT    NULL CHECK (memory_usage_pct BETWEEN 0 AND 100),
    cpu_usage_pct     SMALLINT    NULL CHECK (cpu_usage_pct BETWEEN 0 AND 100),
    wifi_rssi         SMALLINT    NULL,   -- dBm, typically negative (e.g. -58)
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_device_health_status ON device_health (connection_status);

-- =====================================================================
-- COMMANDS — transactional lifecycle, idempotent state-sets
-- =====================================================================

CREATE TABLE commands (
    command_id  VARCHAR(64) PRIMARY KEY,
    target_id   VARCHAR(64) NOT NULL
                  REFERENCES devices(device_id) ON DELETE RESTRICT,
    type        VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    parameters  JSONB       NOT NULL DEFAULT '{}'::jsonb,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','RECEIVED','SUCCESS','FAILED','TIMEOUT')),
    issued_by   VARCHAR(64) NOT NULL,   -- polymorphic: user id | rule id | 'system'
    issued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at TIMESTAMPTZ NULL,
    executed_at TIMESTAMPTZ NULL
);
-- History list filtered by target, newest first (API: GET /commands?targetId=).
CREATE INDEX idx_commands_target_issued ON commands (target_id, issued_at DESC);
-- Timeout sweeper scans only open commands — partial index keeps it tiny.
CREATE INDEX idx_commands_open ON commands (issued_at)
    WHERE status IN ('PENDING','RECEIVED');

-- =====================================================================
-- ACTUATOR STATE — control-plane mirror of sensor_latest, one row/actuator
--   (created after commands: last_command_id references it)
-- =====================================================================

CREATE TABLE actuator_state (
    device_id       VARCHAR(64)  PRIMARY KEY
                      REFERENCES devices(device_id) ON DELETE CASCADE,
    desired_state   VARCHAR(32)  NULL,                          -- last commanded: ON|OFF|OPEN|... (app-whitelisted per device_type)
    reported_state  VARCHAR(32)  NULL,                          -- last device-confirmed via ack/telemetry
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- setpoint, level, mode
    last_command_id VARCHAR(64)  NULL
                      REFERENCES commands(command_id) ON DELETE SET NULL,
    commanded_at    TIMESTAMPTZ  NULL,                          -- set when a command is issued
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()         -- touched on every state change
    -- "Actuator only" is enforced at the app layer (API 422), not a CHECK:
    -- a Postgres CHECK cannot reference devices.category across tables.
);
-- Surfaces actuators whose desired/reported states disagree (in-flight or drifted)
-- for an operator "needs attention" view. Tiny partial index on a low-volume table.
CREATE INDEX idx_actuator_state_drift ON actuator_state (updated_at)
    WHERE desired_state IS DISTINCT FROM reported_state;
-- Upsert on command issue:
--   INSERT INTO actuator_state (device_id, desired_state, last_command_id, commanded_at, updated_at)
--   VALUES (?, ?, ?, now(), now())
--   ON CONFLICT (device_id) DO UPDATE SET
--       desired_state = EXCLUDED.desired_state, last_command_id = EXCLUDED.last_command_id,
--       commanded_at = EXCLUDED.commanded_at, updated_at = now();
-- Upsert on ack/telemetry:
--   ... DO UPDATE SET reported_state = EXCLUDED.reported_state, updated_at = now();

-- =====================================================================
-- RULES
-- =====================================================================

CREATE TABLE rules (
    rule_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(128) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    condition  TEXT         NOT NULL,    -- validated on write by the safe evaluator
    action     TEXT         NOT NULL,    -- validated on write by the safe evaluator
    priority   INTEGER      NOT NULL DEFAULT 0,
    created_by VARCHAR(64)  NOT NULL,
    version    INTEGER      NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- Engine loads enabled rules ordered by priority.
CREATE INDEX idx_rules_enabled_priority ON rules (enabled, priority DESC);

-- =====================================================================
-- ALERTS — status driven by explicit transitions (who acked/resolved)
-- =====================================================================

CREATE TABLE alerts (
    id               BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type             VARCHAR(32) NOT NULL,                 -- SMOKE | ...
    severity         VARCHAR(16) NOT NULL
                       CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    zone             VARCHAR(64) NULL,
    source_device_id VARCHAR(64) NULL
                       REFERENCES devices(device_id) ON DELETE SET NULL,
    message          TEXT        NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'OPEN'
                       CHECK (status IN ('OPEN','ACK','RESOLVED')),
    acknowledged_by  VARCHAR(64) NULL,
    acknowledged_at  TIMESTAMPTZ NULL,
    resolved_by      VARCHAR(64) NULL,
    resolved_at      TIMESTAMPTZ NULL,
    version          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_alerts_status_created ON alerts (status, created_at DESC);
CREATE INDEX idx_alerts_zone           ON alerts (zone);

-- =====================================================================
-- AUDIT LOGS — append-only, range-partitioned by month on ts
-- =====================================================================

CREATE TABLE audit_logs (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY,
    ts         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor      VARCHAR(64)  NOT NULL,
    actor_type VARCHAR(8)   NOT NULL
                 CHECK (actor_type IN ('USER','DEVICE','SYSTEM')),
    event      VARCHAR(64)  NOT NULL,
    target     VARCHAR(128) NULL,
    detail     JSONB        NULL,
    ip         INET         NULL,
    PRIMARY KEY (id, ts)
) PARTITION BY RANGE (ts);

CREATE INDEX idx_audit_actor_ts  ON audit_logs (actor, ts DESC);
CREATE INDEX idx_audit_event_ts  ON audit_logs (event, ts DESC);
CREATE INDEX idx_audit_target_ts ON audit_logs (target, ts DESC);

CREATE TABLE audit_logs_2026_06 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-06-01 00:00:00+00') TO ('2026-07-01 00:00:00+00');
CREATE TABLE audit_logs_2026_07 PARTITION OF audit_logs
    FOR VALUES FROM ('2026-07-01 00:00:00+00') TO ('2026-08-01 00:00:00+00');

-- =====================================================================
-- IDEMPOTENCY KEYS — required by API §1 (24h replay window)
-- =====================================================================

CREATE TABLE idempotency_keys (
    idempotency_key UUID         NOT NULL,
    endpoint        VARCHAR(128) NOT NULL,   -- scope a key to a single route
    request_hash    VARCHAR(64)  NOT NULL,   -- detect same-key / different-body misuse (409)
    response_status SMALLINT     NULL,
    response_body   JSONB        NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,   -- created_at + 24h; row pruned after
    PRIMARY KEY (idempotency_key, endpoint)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
```

---

## 4. Additions beyond the ER diagram

Two things the schema adds that the source ER diagram does not show, both required to honour the API contract:

1. **`idempotency_keys`** — API §1 states `POST /commands` requires an `Idempotency-Key` and the server "returns the original result on retry within a 24 h window" (also supported for device register and credential issue/rotate). That guarantee needs a persisted store of `(key, endpoint) → response`. Without it the contract can't be met across process restarts or multiple instances.
2. **Alert transition attribution** (`acknowledged_by/at`, `resolved_by/at`) — API §10 drives alert status via explicit `:acknowledge` / `:resolve` actions specifically "so the audit trail captures *who* acknowledged *what*." Storing the actor/time on the alert row makes the current `status` self-explaining; the same facts are also written to `audit_logs`.

Smaller, non-structural additions: `created_at`/`updated_at` audit columns and `version` (optimistic-lock) columns on mutable, concurrently-edited tables (`users`, `devices`, `rules`, `alerts`); `grace_expires_at` on `device_credentials` to bound the rotation grace window the system design §7 describes; `rotated_to` on `refresh_tokens` to support rotate-on-use **reuse detection** (§7 / API §2).

---

## 5. Key decisions & trade-offs

### 5.1 `telemetry` has no foreign keys (deliberate)
**Decision:** `telemetry.sensor_id`, `gateway_id`, `zone` are plain columns — no FK to `devices`/`sensors`.
**Buys:** maximum insert throughput on the only high-volume table; device identity is validated at ingest (auth + scope), not on every row insert.
**Costs:** the DB won't reject a reading for an unknown sensor — treated as an immutable fact, cleaned up by retention, not constraints.
**Rejected:** FK-on-insert — needless cost for slow-changing device rows. (System design §4 mandates this.)

### 5.2 Current-state split from history
`sensor_latest` and `device_health` hold one upserted row each so the `< 300 ms` dashboard reads never scan the partitioned `telemetry`. The upsert is **guarded by `ts`** (`WHERE EXCLUDED.ts >= sensor_latest.ts`) so an out-of-order or replayed sample can't move "latest" backwards. Trade-off: slight write amplification and one-sample eventual consistency — accepted per the consistency targets.

### 5.3 Monthly range partitioning + retention-by-drop
`telemetry` and `audit_logs` are `PARTITION BY RANGE (ts)`. Time-range reads prune to the relevant partitions; old-data cleanup is `DROP TABLE telemetry_2025_xx` (a metadata op) instead of a giant `DELETE`. The PK is **composite `(id, ts)`** because Postgres requires the partition key inside any PK/unique constraint; `id` is an internal surrogate never exposed on the wire, so this costs nothing. Automate partition creation/drop with `pg_partman` or a scheduled job.

### 5.4 `device_health` is latest-only, not a heartbeat log
One upserted row per device. Storing every heartbeat would be pure write amplification for data rarely queried historically. If health history is ever needed, add a separate short-retention (e.g. 7-day) partitioned table — don't bloat this one.

### 5.5 "Enums" as `VARCHAR` + `CHECK`
Roles, statuses, severities, scopes, etc. use `VARCHAR` + `CHECK (… IN (…))` rather than native PG `ENUM` types. Adding/removing a permitted value is a one-line `CHECK` change instead of an `ALTER TYPE` migration. Trade-off: the value list is duplicated in app + DB; keep them in sync.

### 5.6 Surrogate keys match the source model
- `users`, `rules`, `refresh_tokens` → **UUID** (safe to expose / distribute).
- `device_id`, `sensor_id`, `command_id` → **opaque application strings** — these are the IDs the API exposes (`gw_office1_01`, `cmd_7a21`).
- `telemetry`, `alerts`, `audit_logs` → **BIGINT identity** internal surrogate (never on the wire; `telemetry.id`/`audit.id` are part of a composite PK with `ts`).

### 5.7 Polymorphic `commands.issued_by` — no FK
A command may be issued by a **user**, a **rule**, or **system**. That's three possible parents, so a single clean FK isn't possible. Stored as a string and documented; integrity is the app's responsibility here. (Alternative — three nullable FK columns + a CHECK that exactly one is set — is heavier than this low-volume column warrants.)

### 5.8 `commands` concurrency via status-guarded updates, not `version`
The ack handler and the timeout sweeper race on the same row. Rather than optimistic locking, transitions use guarded updates, e.g. the sweeper runs `UPDATE … SET status='TIMEOUT' WHERE command_id=? AND status IN ('PENDING','RECEIVED')`; an ack that already advanced the row makes the sweeper's update affect 0 rows. This is simpler than a `version` column for a strict state machine.

### 5.9 `protocols TEXT[]` vs a junction table
The system design models `protocols` as a string array; we keep a native `TEXT[]`. It's a tiny, fixed set (`mqtt`, `http`) on a low-volume table, so a Postgres array is pragmatic. **Trade-off:** this bends strict 1NF. If you ever need to query "all devices speaking protocol X" at scale or attach attributes to a protocol, switch to a `device_protocols(device_id, protocol)` junction table.

### 5.10 `users` soft delete via `status = 'DISABLED'`
The API's `DELETE /users/{id}` sets status `DISABLED` and revokes refresh tokens — there's no hard delete and no separate `deleted_at`. `DISABLED` *is* the soft-delete marker, so every "active users" query filters `status = 'ACTIVE'`.

### 5.11 `actuator_state` — desired vs reported, mirror not history 🆕
**Decision:** one upserted row per actuator, holding **`desired_state`** (what we last commanded) *distinct from* **`reported_state`** (what the device last confirmed via ack/telemetry). It is the control-plane twin of `sensor_latest`: it keeps the dashboard's "is this light/fan ON *right now*" read off the `commands` history table.
**Buys:** the gap `desired_state ≠ reported_state` *is* the in-flight / drift signal the UI renders ("turning on…", "commanded ON but reports OFF → investigate"); the `< 300 ms` toggle-grid read never scans `commands`. The partial drift index makes the operator "needs attention" view cheap.
**Costs / trade-offs:**
- **No cross-table "actuator-only" CHECK.** A Postgres `CHECK` can't reference `devices.category`, so the rule "target must be an `ACTIVE` actuator" is enforced at the API (`422`), exactly as the system design §5.8 specifies — same pattern as `telemetry`/`sensor_latest` having no FK-enforced sensor-ness.
- **`last_command_id` FK → `commands` is `ON DELETE SET NULL`**, not `RESTRICT`: the mirror points at the *latest* command for traceability but must never block command pruning. Full history stays in `commands` + `telemetry`, never here.
- **No `zone` column (unlike `sensor_latest`).** Actuators are low-cardinality (hundreds), so a zone-filtered toggle grid joins `devices` (already indexed on `zone`) cheaply — no need to denormalize zone onto a write path that, unlike telemetry, isn't hot. Denormalize later only if the join shows up in profiling.
- **`desired_state`/`reported_state` are free `VARCHAR(32)`, no `CHECK`.** Valid values vary per `device_type` (`ON/OFF`, `OPEN/CLOSED`, setpoint modes), so the whitelist lives in the app's per-`device_type` validation, not a DB enum.

### 5.12 Manual control adds *no* new lifecycle or audit schema 🆕
Operator commands flow through the **existing** `commands` table and state machine (§5.8) — a human issuer is just a `commands.issued_by` = user-id (the polymorphic column of §5.7). The `Idempotency-Key` on `POST /commands` reuses `idempotency_keys`. The new control-relevant events — `MANUAL_COMMAND` and `SAFETY_OVERRIDE` (system design §7) — are ordinary `audit_logs` rows; `event` is a free-form `VARCHAR(64)`, so capturing them needs **no migration**. The only genuinely new structures are `actuator_state` (§5.11) and the optional `user_zone_grants` (§9).

---

## 6. Access-pattern notes (indexes that matter)

| Query (from API) | Served by | Index |
|---|---|---|
| `GET /telemetry?sensorId=&from=&to=` | `telemetry` (partition-pruned) | `(sensor_id, ts DESC)` |
| `GET /telemetry?zone=&from=&to=` | `telemetry` (partition-pruned) | `(zone, ts DESC)` |
| `GET /current-state?zone=` | `sensor_latest` | PK + `(zone)` |
| `GET /sensors/{id}/latest` | `sensor_latest` | PK |
| `GET /connectivity?zone=` | `device_health` ⋈ `devices` | `device_health(connection_status)`, `devices(zone)` |
| `GET /devices?zone=&category=&status=` | `devices` | `(zone)`, `(category)`, `(status)` |
| `GET /devices/{id}/sensors` | `sensors` | `(gateway_id)` |
| `GET /commands?targetId=&from=&to=` | `commands` | `(target_id, issued_at DESC)` |
| Timeout sweeper | `commands` | partial `(issued_at) WHERE status IN ('PENDING','RECEIVED')` |
| `GET /devices/{id}/actuator-state` (toggle UI) 🆕 | `actuator_state` | PK |
| `GET /actuator-state?zone=` (toggle grid) 🆕 | `actuator_state` ⋈ `devices` | `devices(zone)` (join; actuators low-cardinality) |
| Operator "needs attention" (desired ≠ reported) 🆕 | `actuator_state` | partial `(updated_at) WHERE desired_state IS DISTINCT FROM reported_state` |
| `GET /alerts?status=&zone=&from=&to=` | `alerts` | `(status, created_at DESC)`, `(zone)` |
| `GET /audit-logs?actor=&event=&from=&to=` | `audit_logs` (partition-pruned) | `(actor, ts)`, `(event, ts)`, `(target, ts)` |
| Login / refresh | `users`, `refresh_tokens` | `users(username)` UK, `refresh_tokens(token_hash)` UK |

Both partitioned reads (`telemetry`, `audit_logs`) **require a bounded time window** at the API layer (`422` otherwise) so the planner can prune partitions — the schema and the API contract enforce the same rule from both ends. Don't over-index `telemetry`; the two composite indexes above are the whole story for an append-heavy table.

---

## 7. Partitioning & retention operations

```sql
-- Create next month's partitions ahead of time (run monthly via cron/pg_cron/pg_partman):
CREATE TABLE telemetry_2026_08 PARTITION OF telemetry
    FOR VALUES FROM ('2026-08-01 00:00:00+00') TO ('2026-09-01 00:00:00+00');

-- Retention: drop partitions past the horizon (cheap metadata op, not a DELETE):
DROP TABLE IF EXISTS telemetry_2025_06;     -- e.g. 12-month telemetry retention
DROP TABLE IF EXISTS audit_logs_2024_06;    -- audit kept longer per policy

-- Prune expired idempotency keys and dead refresh tokens (scheduled):
DELETE FROM idempotency_keys WHERE expires_at < now();
DELETE FROM refresh_tokens  WHERE expires_at < now() OR revoked = TRUE;
```

`pg_partman` can manage both creation and retention declaratively; a plain scheduled job is fine at this scale. **Upgrade path (not now):** if chart/aggregation queries dominate, adopt **TimescaleDB** (a Postgres extension — same database) for hypertables + continuous aggregates. No migration to a new system, and the schema above stays intact.

---

## 8. Optional: multi-tenancy (open question #2)

The platform is single-building today (assumption #1). The system design notes that *if* multi-tenant is ever possible, adding `tenant_id` now is "nearly free upfront and painful to retrofit." If you want to keep that door open, apply this pattern to the core tables (`users`, `devices`, `sensors`, `telemetry`, `sensor_latest`, `device_health`, `commands`, `rules`, `alerts`, `audit_logs`):

```sql
-- 1. Add the column (NOT NULL with a backfill default for the existing single tenant).
ALTER TABLE devices ADD COLUMN tenant_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001';

-- 2. Prefix the hot indexes so every query is tenant-scoped first.
--    e.g. telemetry: (tenant_id, sensor_id, ts DESC) and (tenant_id, zone, ts DESC)

-- 3. Enforce isolation at the DB with Row-Level Security (shared-schema model).
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON devices
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
```

Resolve `tenant_id` from the JWT and set it per request (`SET LOCAL app.tenant_id = …`). Keep it out of the schema until tenancy is real — but the column + index ordering above is the cheap insurance the design calls for.

---

## 9. Optional: zone-scoped operator permissions (open question #7) 🆕

The system design's control-authorization matrix (§7) lets an `OPERATOR` command actuators **"(permitted zones)"** — which only means something if the DB records *which* zones each operator may drive. Today authority is global-per-role (role lives on `users`); zone-scoping is **open question §11.7**. The brief's guidance: it's *"cheap upfront, painful to retrofit,"* so decide before build.

**Recommendation: add `user_zone_grants` now** if zone-scoped operator authority is even plausibly wanted — it's a tiny junction table and back-filling it onto a live authorization path later is awkward. If authority will stay strictly global-per-role, skip it.

```sql
-- Many-to-many users ↔ zones. Composite PK (no surrogate needed for a pure grant row).
-- granted_by/at make each grant attributable — it is a security-relevant change.
CREATE TABLE user_zone_grants (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    zone       VARCHAR(64) NOT NULL,
    granted_by VARCHAR(64) NOT NULL,                 -- actor that issued the grant (audit)
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, zone)
);
-- "Who may drive zone X?" and grant-revocation reads.
CREATE INDEX idx_user_zone_grants_zone ON user_zone_grants (zone);
```

**Semantics.** Empty/absent grants for a user = no zone-scoped authority (deny by default); the check is `@PreAuthorize` *plus* a `user_zone_grants` lookup at the command endpoint, never in the UI. `ADMIN`/`SUPER_ADMIN` bypass the zone filter (they command any zone per the §7 matrix), so the table is consulted only for `OPERATOR` and `TECHNICIAN` (both zone-scoped for routine actuators; `TECHNICIAN` cannot command safety actuators at all). Granting/revoking a zone is itself an audited event (`ZONE_GRANT` / `ZONE_REVOKE` in `audit_logs`). This is **not** in the core DDL above and ships as Flyway `V4` only if adopted (§10).

---

## 10. Flyway migrations 🆕

Migrations live in `src/main/resources/db/migration` and follow `V<n>__<desc>.sql`. Existing baseline: **`V1__init_schema.sql`** (all core tables) and **`V2__add_technician_role.sql`** (adds `TECHNICIAN` to `users.role`). The operator-control update adds:

| Version | File | Contents | Status |
|---|---|---|---|
| **V3** | `V3__add_actuator_state.sql` | `actuator_state` table + drift partial index | **Created** — required for operator control |
| **V4** | `V4__add_user_zone_grants.sql` | `user_zone_grants` table + zone index | **Optional** — only if zone-scoped authority is adopted (§9) |

`V3` is ordered after `commands` (created in `V1`) because `actuator_state.last_command_id` references it — Flyway applies `V1 → V2 → V3` in order, so the dependency is satisfied. Both are pure additive `CREATE TABLE`s: no backfill, no lock on existing hot tables, safe to apply online.

```sql
-- V3__add_actuator_state.sql  (created in the repo)
CREATE TABLE actuator_state (
    device_id       VARCHAR(64)  PRIMARY KEY
                      REFERENCES devices(device_id) ON DELETE CASCADE,
    desired_state   VARCHAR(32)  NULL,
    reported_state  VARCHAR(32)  NULL,
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_command_id VARCHAR(64)  NULL
                      REFERENCES commands(command_id) ON DELETE SET NULL,
    commanded_at    TIMESTAMPTZ  NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_actuator_state_drift ON actuator_state (updated_at)
    WHERE desired_state IS DISTINCT FROM reported_state;
```

```sql
-- V4__add_user_zone_grants.sql  (write only if §9 is adopted)
CREATE TABLE user_zone_grants (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    zone       VARCHAR(64) NOT NULL,
    granted_by VARCHAR(64) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, zone)
);
CREATE INDEX idx_user_zone_grants_zone ON user_zone_grants (zone);
```

> **Note — no migration needed for audit.** The new `MANUAL_COMMAND` / `SAFETY_OVERRIDE` (and, with §9, `ZONE_GRANT` / `ZONE_REVOKE`) events are plain `audit_logs` rows; `event VARCHAR(64)` already accepts them. Likewise manual commands and their `Idempotency-Key` reuse `commands` and `idempotency_keys` unchanged.

---

## 11. Summary verdict

✅ **The schema matches the architecture's grain.** It implements the system design's load-bearing decisions — the current-state/history split, FK-free high-volume telemetry, monthly partitioning with retention-by-drop, the command state machine, hashed/rotating credentials with a grace slot, and append-only partitioned audit — and the API's contracts (opaque string IDs, numeric-XOR-boolean readings, mandatory time-window scoping on partitioned reads, idempotent retries). The **operator control plane** (this revision) slots in cleanly: one new mirror table (`actuator_state`, §5.11) reusing the existing command lifecycle, audit, and idempotency machinery with no churn to hot tables (§5.12). The two genuine watch-items inherited from the design remain operational, not schema-level: **telemetry growth** (handled by partitioning + retention here) and the **MQTT broker SPOF** (outside the database). The schema judgement calls worth a second look before build are the **`protocols` array vs junction table** (§5.9), whether to **add `tenant_id` now** (§8), and whether to **adopt zone-scoped operator grants** (§9 / `V4`).
