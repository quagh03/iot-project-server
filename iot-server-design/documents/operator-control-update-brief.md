# Update Brief — Operator Device Control (for API & DB design)

**Purpose:** hand-off note summarizing the changes just made to `iot-platform-system-design.md` so the next session can design the **REST API contracts** and **DB schema** for admin/operator device control. The system-design doc is authoritative for *decisions*; this file is the short list of *what to build next*.

---

## What changed in the system design

Added a first-class **operator control plane**: authorized dashboard users can command actuators (on/off, setpoints), see current actuator state, and track outcomes — through the **same command pipeline** the rule engine already uses (no parallel path).

| § | Change |
|---|--------|
| §1 | New functional requirement: operator device control via dashboard |
| §4 | New `actuator_state` table + design note |
| §5.8 (new) | Control plane decision: **async** `202` + poll/push outcome model + sequence diagram |
| §5.7 | Trade-off summary row for the control API |
| §7 | Control-authorization RBAC matrix + **safety-interlock** rule |
| §11 | Open questions: outcome delivery, safety-override policy, zone-scoped permissions |

---

## DB design — what to add

### New table: `actuator_state` (control-plane mirror of `sensor_latest`)
One row per actuator; current state for the toggle UI, kept off the `commands` history table.

| Column | Type | Notes |
|--------|------|-------|
| `device_id` | PK, FK → `devices` | actuator only |
| `desired_state` | string | last commanded (`ON`/`OFF`/...) — set on command issue |
| `reported_state` | string | last device-confirmed — set on ack/telemetry |
| `attributes` | jsonb | setpoint, level, mode |
| `last_command_id` | FK → `commands` | |
| `commanded_at` | timestamptz | |
| `updated_at` | timestamptz | |

- **Key idea:** `desired_state` vs `reported_state` gap = the in-flight / drift signal the UI renders ("turning on…", "commanded ON but reports OFF").
- `commands` table is unchanged — it already has the full lifecycle (`PENDING → RECEIVED → SUCCESS/FAILED/TIMEOUT`).
- **Decide (open question §11.7):** if operator authority is **zone-scoped**, add a `user_zone_grants` table (`user_id`, `zone`) now — cheap upfront, painful to retrofit.

---

## API design — what to add

### Endpoints (contracts to be designed)
- **Issue command** — `POST /devices/{id}/commands` → returns **`202 Accepted` `{ command_id, status: PENDING }`**. Does **not** block on the device.
- **Poll outcome** — `GET /commands/{command_id}` → current lifecycle status.
- **List/read actuator state** — expose `actuator_state` for the dashboard (desired vs reported).
- *(Optional)* **Bulk/zone control** — sugar that fans out into N single-device commands, each with its own `command_id`/ack/audit. Don't create a second lifecycle.

### Rules the contracts must enforce
1. **Async model** — `202` + poll (or SSE/WebSocket push if adopted). UI models an in-flight state; timeout sweeper guarantees a terminal status, so polling is bounded.
2. **Idempotency** — require `Idempotency-Key` header on issue (one command per double-click/retry).
3. **Validation `422`** — target must be an **ACTIVE actuator**; reject sensors/gateways and `SUSPENDED`/`DECOMMISSIONED`; action + params **whitelisted per `device_type`**.
4. **Authorization** (`@PreAuthorize`, enforced server-side):

   | Role | Read state | Routine actuators (light/AC/curtain) | Safety actuators (exhaust/smoke-linked) | Override active safety rule |
   |------|:--:|:--:|:--:|:--:|
   | `VIEWER` | ✅ | ✗ | ✗ | ✗ |
   | `OPERATOR` | ✅ | ✅ (permitted zones) | ✅ (ON/escalate only) | ✗ |
   | `ADMIN` | ✅ | ✅ | ✅ | ✗ |
   | `SUPER_ADMIN` | ✅ | ✅ | ✅ | ✅ (explicit + confirmed + audited) |

5. **Safety interlock** — a manual command contradicting an active safety action (e.g. `exhaust OFF` during an open smoke alert) → **`409 safety-interlock`** for everyone below `SUPER_ADMIN`; override needs an explicit flag + reason and logs a `SAFETY_OVERRIDE` audit event. Rule engine outranks manual control; fail-safe always.
6. **Audit** — every manual command logged: actor, `USER`, source IP, target, action, `command_id`.
7. **Rate limit** — manual commands fall under the per-user limit (also abuse signal).

---

## Open questions to resolve before/while designing (from §11)
1. **Outcome delivery:** polling vs SSE/WebSocket push for terminal state.
2. **Safety-override policy:** which actuators are "safety-critical"; is `SUPER_ADMIN` override allowed; what justification it must capture.
3. **Zone-scoped permissions:** global-per-role vs per-user zone grants (→ DB table decision above).

---

**Reference:** full reasoning in `iot-platform-system-design.md` §1, §4, §5.8, §7, §11.
