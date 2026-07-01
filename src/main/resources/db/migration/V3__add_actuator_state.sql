-- =====================================================================
-- Operator device control plane — actuator_state
-- =====================================================================
-- Control-plane mirror of sensor_latest: one row per actuator holding its
-- current state for the dashboard toggle UI, kept OFF the commands history
-- table. desired_state (what we last commanded) is kept distinct from
-- reported_state (what the device last confirmed) — the gap between them is
-- the in-flight / drift signal the UI renders ("turning on…", "commanded ON
-- but reports OFF"). Upsert desired_state when a command is issued;
-- reported_state on ack/telemetry.
--
-- Created after commands (V1) because last_command_id references it.
-- "Actuator only" is enforced at the application layer (API 422), not by a
-- cross-table CHECK — Postgres CHECK cannot reference devices.category.

CREATE TABLE actuator_state (
    device_id       VARCHAR(64)  PRIMARY KEY
                      REFERENCES devices(device_id) ON DELETE CASCADE,
    desired_state   VARCHAR(32)  NULL,                      -- last commanded: ON|OFF|OPEN|... (app-whitelisted per device_type)
    reported_state  VARCHAR(32)  NULL,                      -- last device-confirmed via ack/telemetry
    attributes      JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- setpoint, level, mode
    last_command_id VARCHAR(64)  NULL
                      REFERENCES commands(command_id) ON DELETE SET NULL,
    commanded_at    TIMESTAMPTZ  NULL,                      -- set when a command is issued
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()     -- touched on every state change
);

-- Surfaces actuators whose desired/reported states disagree (in-flight or drifted)
-- for an operator "needs attention" view. Tiny partial index on a low-volume table.
CREATE INDEX idx_actuator_state_drift ON actuator_state (updated_at)
    WHERE desired_state IS DISTINCT FROM reported_state;

-- Upsert on command issue:
--   INSERT INTO actuator_state (device_id, desired_state, last_command_id, commanded_at, updated_at)
--   VALUES (?, ?, ?, now(), now())
--   ON CONFLICT (device_id) DO UPDATE SET
--       desired_state = EXCLUDED.desired_state,
--       last_command_id = EXCLUDED.last_command_id,
--       commanded_at = EXCLUDED.commanded_at,
--       updated_at = now();
-- Upsert on ack/telemetry:
--   ... DO UPDATE SET reported_state = EXCLUDED.reported_state, updated_at = now();
