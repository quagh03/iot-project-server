# Office IoT Platform — Operations & Security Runbook

**Purpose:** Phase 10 (Hardening, Observability, Ops & Deployment) split into what's actual
application code (built — see the implementation plan's Phase 10 status note) and what's
real infrastructure that has to be provisioned outside this repo before it's true in
production. This document is the second half: the design decisions, procedures, and
checklists for the infra-only items. It assumes the reader has System Design §7 (threat
model, secrets table, OWASP mapping) open alongside it — this doc extends that, it doesn't
repeat it.

**What Phase 10 already built in code** (not repeated here in detail — see the
implementation plan's Phase 10 section for the full list): Redis-backed rate limiting,
asymmetric JWT signing with `kid`-based key rollover and a public JWKS endpoint, Micrometer
metrics + Prometheus scrape endpoint (ADMIN-only) + Actuator liveness/readiness probes, and
in-process detection/alerting for auth-failure bursts, refresh-token reuse, rate-limit
spikes, 403 spikes, command-timeout bursts, and safety-sensor telemetry gaps (all raised as
`Alert` rows via the Phase 8 `AlertService`). Two real bugs were also fixed in passing:
`application-prod.yaml`'s `iot.redis.host/port` were never actually wired to Spring's Redis
connection factory (fixed — now `spring.data.redis.*`), and `iot.mqtt.username/password`
were accepted by config but never applied to the Paho connection (fixed).

---

## 1. Broker HA

**Decision: EMQX for production, Mosquitto stays dev/test-only.**

Mosquitto (what local dev and the test suite use via Testcontainers) has no built-in
clustering — a single Mosquitto process is a hard SPOF, and per System Design §8 the
broker is the #1 SPOF-mitigation priority for this system (a broker outage during a fire
is exactly the scenario the safety design revolves around).

| Option | Clustering | MQTT 5 shared subscriptions | Dynamic ACL plugin ecosystem | Operational familiarity |
|---|---|---|---|---|
| **EMQX** (recommended) | Native, mature | Yes | Rich (HTTP auth/ACL, JWT auth built in) | Moderate learning curve, well-documented |
| HiveMQ | Native (Enterprise for full clustering) | Yes | Rich, but clustering is a paid tier | Enterprise-oriented licensing |
| Mosquitto + `mosquitto-go-auth` bridge pairs | No native clustering; would need an external LB + bridge topology (fragile, self-built) | No | Plugin exists but clustering itself isn't Mosquitto's design center | Simple for single-node, painful to scale |

EMQX's built-in JWT authentication plugin is also the natural fit for §2 below (it can
validate a device's OAuth2 access token directly against this app's JWKS endpoint — no
custom auth-webhook service needs to be written).

**Rollout:** stand up a 3-node EMQX cluster (odd node count for quorum) behind a network
load balancer; `iot.mqtt.broker-url` points at the LB, not an individual node.
`MqttClientLifecycle`'s `automaticReconnect` + `cleanSession=false` already give this app
side of the connection the persistent-session/reconnect behavior it needs — no app code
changes required to point at a clustered broker, only the URL and `iot.mqtt.username`/
`password` (or a client certificate, if EMQX is configured for mTLS instead).

**Rehearsal before calling this done:** kill one EMQX node under load, confirm in-flight
QoS-1 messages aren't lost and `MqttClientLifecycle` reconnects to a surviving node without
manual intervention.

---

## 2. Per-device broker ACLs

**Status: designed here, not implemented — blocked on an unresolved device-team-spec
decision, not on anything in this app.**

The device-team spec (§8.5) explicitly flags the MQTT authentication mechanism as
unconfirmed: *"Credential: OAuth2 access token (as username/password or auth field per
broker) ⚠️ confirm exact MQTT auth mechanism."* Per-device topic ACLs can't be built until
that's answered, because the ACL rule has to key off *something* the broker can verify
per-connection, and today `mosquitto.conf` runs `allow_anonymous true` — there is no
broker-asserted device identity to attach an ACL to at all yet.

**The design, ready to implement once the auth mechanism is confirmed:**

1. **Confirm the MQTT auth mechanism** (device-team spec §8.5 open question). Recommended:
   the device presents its OAuth2 device-token (from `POST /oauth2/token`, already built)
   as the MQTT password, with `client_id` as the MQTT username. This reuses the existing
   device-credential/scope model with zero new provisioning flow.
2. **EMQX JWT auth plugin** validates the presented token directly against this app's JWKS
   endpoint (`GET /api/v1/.well-known/jwks.json`, built in Phase 10) — no custom auth
   webhook needed. The token's `sub` claim (device id) becomes the broker's notion of
   "who is this connection."
3. **ACL rule, expressed with EMQX's `%u`/`%c` placeholder substitution** (or the
   equivalent in whatever broker is chosen):
   ```
   # Gateways: publish only their own telemetry/heartbeat, nothing else
   topic write iot/telemetry/+/%u
   topic write iot/heartbeat/%u
   topic read  iot/status/%u        # LWT is broker-published, but re-reads should still be scoped

   # Actuators: subscribe only their own commands, ack only their own acks
   topic read  iot/command/%u
   topic write iot/command_ack/%u
   topic write iot/heartbeat/%u
   ```
   `%u` (MQTT username = `client_id`) works if `client_id` is set to the device's own
   `device_id` at provisioning time — confirm this convention is followed consistently
   (the current test fixtures use `cli_gw_1` as a client id distinct from `gw_1` the
   device id, which would NOT satisfy `%u`-based ACLs as written above; either standardize
   `client_id == device_id`, or use a broker ACL plugin that can look up the device_id
   from the client_id via a webhook back to this app's `GET /v1/devices?...` — more
   flexible, more moving parts).
4. **Verify a device cannot publish/subscribe another device's topics** — this is the
   actual test to run once built: connect as device A, attempt to publish to device B's
   command-ack topic, confirm the broker denies it (not this app — the whole point is the
   broker enforces it before the message ever reaches `CommandAckMqttListener`).
5. **ACL denial → detection signal.** Once ACL enforcement exists, wire EMQX's ACL-deny
   webhook/event into `SecurityDetectionService` (a new `recordBrokerAclDenial(deviceId)`
   method, same burst-counter shape as the other signals) — this is explicitly one of the
   §7 required detection signals and currently has no implementation because there's
   nothing to detect yet.

---

## 3. Encryption at rest & backups

**Encryption at rest:** enable it at the storage layer, not in application code — this is
a property of wherever Postgres's data volume actually lives (e.g. an encrypted EBS volume
on AWS, encrypted persistent disk on GCP, or LUKS on bare metal). No Postgres extension
(pgcrypto etc.) is needed for whole-volume encryption; reserve `pgcrypto` for
column-level encryption only if a specific field needs it independent of the disk (none
currently does — passwords are Argon2id-hashed, not merely encrypted, and secrets are
write-once hashed the same way).

**Backups — the actual runbook:**
1. Enable WAL archiving (`archive_mode = on`, `archive_command` shipping to encrypted
   object storage) for point-in-time recovery, not just nightly `pg_dump` snapshots — this
   system's telemetry/audit tables are append-only and continuously written, so PITR
   matters more here than for a typical CRUD app.
2. Nightly `pg_basebackup` (or the managed-Postgres equivalent) to the same encrypted
   object storage, retained per the org's compliance window.
3. **Rehearse the restore before trusting it**: quarterly, restore the latest backup +
   WAL replay to a scratch instance, run `SELECT count(*)` sanity checks against
   `telemetry`, `audit_logs`, `commands`, confirm partition boundaries are intact
   (`PartitionManager`'s naming convention — `{table}_{yyyy}_{MM}` — must still match after
   restore), and time the whole exercise so the RTO is a known number, not a guess.
4. Document the restore runbook's exact commands (provider-specific — fill in once the
   hosting target is chosen) in this file once rehearsed at least once, per the Phase 10
   DoD ("encrypted backup restored to a clean instance end-to-end").

---

## 4. Least-privilege DB user & network isolation

**DB role split:** today the app and Flyway share one Postgres role — the running backend
can DDL its own schema, which is more privilege than a request-serving process should
ever need (a SQL-injection-adjacent bug or a compromised dependency shouldn't be able to
`DROP TABLE`). Split into two roles before production:

```sql
-- Migration role (used only by the Flyway job/init-container, never by the running app)
CREATE ROLE iot_migrator LOGIN PASSWORD '...';
GRANT ALL PRIVILEGES ON DATABASE iot TO iot_migrator;

-- Runtime role (used by the Spring Boot app's DataSource)
CREATE ROLE iot_app LOGIN PASSWORD '...';
GRANT CONNECT ON DATABASE iot TO iot_app;
GRANT USAGE ON SCHEMA public TO iot_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO iot_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO iot_app;
-- Deliberately no CREATE/DROP/ALTER — schema changes only ever go through iot_migrator.
```

Wire `iot_migrator` as Flyway's datasource (a separate `spring.flyway.*` datasource, or run
migrations as a pre-deploy step/init-container with its own connection string) and
`iot_app`/`DB_USERNAME`/`DB_PASSWORD` (already the `application-prod.yaml` env vars) as the
one the running app actually connects with.

**Network isolation:** the running backend should be the *only* thing that can reach
Postgres, Redis, and the MQTT broker on their data ports — enforced via security
groups/firewall rules/K8s NetworkPolicy (whichever the hosting target uses), not
application config. Nothing in this repo can enforce this; it's a deployment-time
topology decision. Document the actual security-group/NetworkPolicy rules here once the
hosting target is chosen.

---

## 5. Privacy: occupancy/presence data classification

System Design §7's OWASP IoT mapping already flags occupancy/presence readings (motion,
light-based presence inference, and — notably for this system — heartbeat *patterns*, not
just the heartbeat payload itself) as sensitive. Concretely for this codebase:

- **Sensor types carrying occupancy signal:** `light` (on/off state can infer
  presence/absence patterns over time) and any future PIR/motion sensor type. `smoke`/
  `temp`/`hmid` are not occupancy-sensitive on their own.
- **Today's access control:** `VIEWER` role can read all current-state/history endpoints
  (`GET /v1/current-state`, `GET /v1/telemetry`) with no per-sensor-type restriction. This
  is broader than the OWASP IoT mapping recommends for occupancy-classified data.
- **Recommended tightening** (not yet built — flagging as a follow-up, not silently
  deferring): either (a) a dedicated `occupancy:read` scope/role tier stricter than plain
  `VIEWER`, or (b) aggregation — expose occupancy-derived sensor types only as
  zone-level roll-ups (already partially true for `GET /v1/connectivity`) rather than
  per-sensor raw history, and gate the raw per-sensor history behind `OPERATOR`+.
- **Retention:** no sensor-type-specific retention policy exists today —
  `PartitionConfig.retentionMonths` is one global knob for `telemetry`. If occupancy data
  needs shorter retention than safety-sensor data (a defensible privacy stance — you don't
  need six months of light-switch history but you might want six months of smoke-sensor
  history for incident forensics), that requires per-sensor-type retention, which
  `PartitionManager` doesn't support (it drops whole monthly partitions, not filtered
  subsets). Revisit if/when this distinction becomes a real compliance requirement.

---

## 6. JWT signing-key rotation — cadence and procedure

The mechanism (asymmetric RSA keys, `kid`-tagged tokens, a JWKS endpoint, `retiredKeys`
verification-only list) is built — `security.JwtKeyManager`/`JwtKeyProperties`. This
section is the *procedure* for using it.

**Cadence:** rotate the active signing key quarterly, or immediately on suspected key
compromise (treat it the same urgency as a device-compromise event — see §7).

**Procedure (zero-downtime, no token invalidation):**

1. Generate a new RSA-2048 (or larger) keypair in the KMS/secrets manager. Assign it a new
   `kid` (recommend a sortable, human-readable scheme like `2026-Q3` rather than a random
   UUID — makes `GET /api/v1/.well-known/jwks.json` and audit logs easier to reason about
   during an incident).
2. **Add the new key as `active`** (`JWT_ACTIVE_KID`, `JWT_ACTIVE_PRIVATE_KEY_PEM`,
   `JWT_ACTIVE_PUBLIC_KEY_PEM`) **and simultaneously move the previous active key into
   `retiredKeys`** (`JWT_RETIRED_KEYS`, public key only — never carry a private key into
   `retiredKeys`). Deploy this as one config change, one rolling restart.
3. From this point: new tokens are signed with the new key; tokens signed by the previous
   key continue to verify successfully (`JwtKeyManagerTest` in this repo proves this exact
   property) because its public key is still in the published JWKS.
4. **Wait out the longest-lived token type** before removing the old key from
   `retiredKeys` entirely — that's the refresh token TTL (`iot.security.jwt.refresh-token-ttl`,
   30 days by default), not the 1-hour access token. Removing it sooner would reject a
   still-valid refresh token mid-life, forcing an unnecessary re-login.
5. After that window, delete the old key from `JWT_RETIRED_KEYS` and destroy the private
   key material in the KMS (it should never have left the KMS in the first place — the app
   only ever receives the PEM strings via env var injection at container start, never
   fetches directly from the KMS API in current code; if you *do* wire direct KMS SDK
   integration later, prefer that over env-var injection, since it removes the key from
   ever appearing in a process's environment listing).

**On suspected compromise:** skip step 4's waiting period — rotate immediately, and treat
every token issued in the suspected compromise window as untrustworthy regardless of
`kid` (this requires denylisting by issuance time, not just by `jti`/refresh-hash, which
today's `TokenDenylist` SPI doesn't support — a compromised *signing key*, as opposed to a
compromised individual token, is a gap the current denylist design doesn't fully close;
flagging this explicitly rather than pretending key rotation alone fully mitigates it).

---

## 7. Device-compromise containment runbook

Referenced by the implementation plan's Phase 10 DoD ("device-compromise runbook
rehearsed"). Concrete steps using this app's actual built endpoints:

1. **Suspend immediately** (reversible, buys investigation time without full teardown):
   ```
   POST /api/v1/devices/{deviceId}:suspend
   ```
   This disables the device's credentials (Phase 3) — it can no longer mint a new OAuth2
   token, so any *new* connection attempt fails at `/oauth2/token`. An already-connected
   MQTT session is not force-disconnected by this call alone (no broker ACL enforcement
   yet — see §2); if the broker choice from §1 is live with JWT auth, a suspended device's
   existing token remains valid for its own token TTL (1h by default) unless the broker is
   also told to drop the connection. Until §2 is built, follow suspension immediately with
   a manual broker-side kick of that client's connection.

2. **Audit-review everything that identity did**, bounded to a time window around the
   suspected compromise:
   ```
   GET /api/v1/audit-logs?actor={deviceId}&from={window_start}&to={window_end}
   GET /api/v1/commands?targetId={deviceId}&from={window_start}&to={window_end}
   GET /api/v1/telemetry?sensorId={sensorId}&from={window_start}&to={window_end}
   ```
   Look specifically for: commands issued *to* an actuator identity that shouldn't have
   been able to (target/actor mismatch), telemetry values inconsistent with neighboring
   sensors (see step 4), and any `MANUAL_COMMAND`/`SAFETY_OVERRIDE` audit entries in the
   window that don't correspond to a known legitimate operator action.

3. **Cross-check neighboring sensors for the compromise window** — the blast radius must
   be provably one device, never inferred to be the whole fleet:
   ```
   GET /api/v1/telemetry?zone={zone}&from={window_start}&to={window_end}
   ```
   filtered to the same zone, compared reading-by-reading against the suspect device's
   values for the same sensor type. Corroborating readings from other devices in the zone
   is the evidence that confirms (or refutes) whether the compromise affected physical
   reality or was purely a data-integrity/spoofing event contained to the one identity.

4. **Decommission if confirmed** (irreversible — only after steps 2-3 conclude compromise
   is real, since decommission is terminal per the device lifecycle state machine):
   ```
   POST /api/v1/devices/{deviceId}:decommission
   ```
   This revokes credentials permanently and is audited (`DEVICE_DECOMMISSION`). Re-onboarding
   requires registering a new device identity from scratch — decommissioned ids are
   terminal, not reusable, by design (Phase 3).

5. **Post-incident:** if the compromise vector was a credential leak (not a code/protocol
   flaw), rotate credentials for *other* devices of the same hardware/firmware batch as a
   precaution (`POST /api/v1/devices/{deviceId}/credentials:rotate`, Phase 3, has a grace
   window so this can be done without a hard cutover). If the vector was systemic (a
   firmware vulnerability, a weak provisioning process), that's a finding for the
   device-team spec, not something this backend can remediate alone.

**Rehearse this end to end** (steps 1-4 against a throwaway test device) before
considering the runbook done, per the Phase 10 DoD.

---

## 8. Standards mapping confirmation (OWASP API Security Top 10 / OWASP IoT Top 10)

This is a confirmation pass over System Design §7's mapping table, noting what Phase 10
code work closes vs. what's still open pending the infra items above.

| OWASP category | Status after Phase 10 code work | Still open |
|---|---|---|
| API1 Broken Object Level Authorization | Addressed — `@PreAuthorize` per endpoint, ownership checks on device/command/rule resources | — |
| API2 Broken Authentication | Addressed — Argon2id, refresh rotation+reuse-cascade, now asymmetric JWT + `kid` rollover | KMS integration for real key custody (§6 procedure assumes it) |
| API4 Unrestricted Resource Consumption | Addressed — rate limiting now Redis-backed for multi-instance | — |
| API5 Broken Function Level Authorization | Addressed — role hierarchy + scope checks | Zone-scoped authority was deliberately not adopted (Phase 6 decision) — global-per-role only |
| API7 Server-Side Request Forgery | N/A — no user-supplied URLs fetched server-side | — |
| API8 Security Misconfiguration | Partially — security headers, TLS enforcement in prod config | Least-privilege DB role (§4) and network isolation (§4) still infra-only |
| API9 Improper Inventory Management | Addressed — device registry is the single source of truth for what's allowed to talk to the API | — |
| API10 Unsafe Consumption of APIs | N/A — no third-party API consumption in this system | — |
| IoT1 Weak/Guessable Passwords | Addressed — Argon2id, write-once device secrets | — |
| IoT2 Insecure Network Services | Partially — MQTTS support exists in `MqttProperties`/`MqttClientLifecycle` | Broker ACLs (§2), broker HA (§1) still open |
| IoT3 Insecure Ecosystem Interfaces | Addressed — one funnel for MQTT+HTTP, consistent validation | — |
| IoT4 Lack of Secure Update Mechanism | N/A — firmware update is the device team's concern, out of this backend's scope | — |
| IoT5 Use of Insecure/Outdated Components | Not addressed by Phase 10 (SCA scanning explicitly out of scope per this session) | CI dependency scanning |
| IoT7 Insecure Data Transfer/Storage | Partially — hashed secrets/passwords, TLS in transit config | Encryption at rest (§3) still infra-only |
| IoT8 Lack of Device Management | Addressed — full lifecycle (register/activate/suspend/decommission), now with a rehearsed compromise runbook (§7) | — |
| IoT9 Insecure Default Settings | Addressed — `allow_anonymous true` is explicitly dev/test-only, prod config requires real secrets with no fallback defaults | — |
| IoT10 Lack of Physical Hardening | N/A — physical security is a facilities concern, not this backend's | — |

**Explicitly out of scope for this pass** (per this session's agreed Phase 10 scope): CI
security gates (SCA/SAST/secret scanning) and the full production deployment pipeline —
tracked as open items in the implementation plan, not silently dropped.
