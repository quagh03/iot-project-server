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
                       role          VARCHAR(16)  NOT NULL
                           CHECK (role IN ('SUPER_ADMIN','ADMIN','OPERATOR','VIEWER')),
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