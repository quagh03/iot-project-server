# IoT Project Server

Backend for an **office IoT monitoring & control platform**: gateways publish sensor telemetry (temperature, humidity, smoke, …) over MQTT, a rule engine reacts to readings by driving actuators (lights, AC, exhaust fans, curtains) and raising alerts, and operators supervise everything — live state, device health, manual actuator control, alerts, audit trail — through a REST API consumed by a dashboard frontend.

The system is a **modular monolith**: one Spring Boot deployable with strict internal module seams (`registry`, `telemetry`, `command`, `rules`, `alert`, `health`, `audit`, `security`, `mqtt`, `api`, `common`), one PostgreSQL database, one MQTT broker. Telemetry history and audit logs are month-partitioned; live dashboard reads are served from dedicated current-state tables, never the big history partitions.

**Key flows**

- **Telemetry**: device → MQTT (`iot/telemetry/{zone}/{gateway_id}`, HTTP fallback) → validation against the device registry → history + current-state upsert → async rule evaluation.
- **Commands**: operator (`POST /api/v1/commands`) or rule engine → persisted lifecycle `PENDING → RECEIVED → SUCCESS/FAILED/TIMEOUT` → MQTT dispatch (`iot/command/{device_id}`) → device ack correlation, with a 30 s timeout sweeper so every command reaches a terminal state.
- **Security**: OAuth2/JWT (RS256 + JWKS) for users with a 5-level RBAC hierarchy, client-credentials tokens with scopes for devices, refresh-token rotation with reuse detection, token denylist, rate limiting, append-only audit log, and built-in anomaly detection that raises alerts (auth-failure bursts, command-suppression suspicion, safety-sensor telemetry gaps, …).

📚 Detailed docs live in [`iot-server-design/documents/`](iot-server-design/documents/) — start with the [integration guide](iot-server-design/documents/iot-platform-integration-guide.md) (FE + device teams), then the system design, API design, and ops runbook.

## Technologies

| Concern | Technology |
|---|---|
| Language / runtime | Java 21 (Gradle toolchain) |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Security, OAuth2 Resource Server, Actuator) |
| Database | PostgreSQL 17, Flyway migrations, monthly range partitioning for `telemetry` / `audit_logs` |
| MQTT | Eclipse Paho v3 client · Mosquitto broker (dev); persistent session, QoS 1 |
| Cache / scale-out (optional) | Redis 8 — rate-limit counters & token denylist when `iot.redis.enabled=true` |
| Auth | JWT RS256 with `kid` rollover + JWKS endpoint, Argon2id password hashing (BouncyCastle) |
| Observability | Micrometer + Prometheus registry, liveness/readiness probes |
| API docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Testcontainers (Postgres, MQTT, Redis), Awaitility, Spring Security Test |
| Build | Gradle (wrapper included), Lombok |

## Getting started

### Prerequisites

- **JDK 21**
- **Docker** (with Compose) — for PostgreSQL, the Mosquitto MQTT broker, and (optional) Redis

### 1. Start local infrastructure

```bash
cd src/main/docker/compose
docker compose up -d
```

This brings up:

| Service | Port | Notes |
|---|---|---|
| PostgreSQL 17 | `5432` | db `iot`, user/password `postgres`/`postgres` (override via `POSTGRES_*` env) |
| Mosquitto | `1883` (+ `9001` ws) | anonymous, no TLS — dev only |
| Redis 8 | `6379` | optional; the app ignores it unless `iot.redis.enabled=true` |
| RedisInsight | `5540` | optional Redis UI |

### 2. Run the application

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

On the `local` profile the app:

- applies all Flyway migrations to `jdbc:postgresql://localhost:5432/iot`,
- connects to the broker at `tcp://localhost:1883` (an unreachable broker never blocks startup — it retries in the background),
- generates an ephemeral RSA keypair for JWT signing (no config needed),
- seeds dev fixtures: one user per role (password `changeme` for all) — **`admin`** (SUPER_ADMIN), **`manager`** (ADMIN), **`operator`** (OPERATOR), **`tech`** (TECHNICIAN), **`user`** (VIEWER) — plus two live zones (`office_1`, `meeting`) with gateways, sensors and actuators in every lifecycle state, ~6 h of telemetry history and current state, device health, commands across the lifecycle (one deliberately drifted actuator), enabled/disabled rules (including the smoke → exhaust-fan safety rule), and alerts in all three statuses.

The API is now at **`http://localhost:8080/api/v1`**, Swagger UI at **`http://localhost:8080/api/v1/swagger-ui.html`**, health probe at `http://localhost:8080/actuator/health`.

### 3. Smoke-test it

```bash
# Log in with the seeded admin
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"changeme"}'
# → { "accessToken": "...", "refreshToken": "...", "role": "SUPER_ADMIN", ... }

# Use the token
TOKEN=<accessToken>
curl -s http://localhost:8080/api/v1/devices -H "Authorization: Bearer $TOKEN"
curl -s http://localhost:8080/api/v1/current-state -H "Authorization: Bearer $TOKEN"
```

Publish a test reading over MQTT (e.g. with `mosquitto_pub`) and watch it land in current-state:

```bash
mosquitto_pub -h localhost -t 'iot/telemetry/office_1/gw_office1_01' -q 1 -m '{
  "timestamp": "2026-07-02T10:15:30Z",
  "zone": "office_1",
  "gateway_id": "gw_office1_01",
  "sensors": [ { "id": "s_temp_1", "type": "temp", "value": 23.5, "unit": "C" } ]
}'
```

### Running tests

```bash
./gradlew test
```

Integration tests use **Testcontainers** and spin up their own Postgres/MQTT/Redis containers — Docker must be running; no manual infra setup or running compose stack is required.

### Production notes

The `prod` profile expects externalized configuration (no defaults): `DB_URL`/`DB_USERNAME`/`DB_PASSWORD`, `MQTT_BROKER_URL`/`MQTT_CLIENT_ID` (+ optional `MQTT_USERNAME`/`MQTT_PASSWORD`), real RSA PEM key material for JWT signing (`iot.security.jwt.keys.*`), and Redis (`spring.data.redis.*` with `iot.redis.enabled=true`) for cluster-wide rate limits and the token denylist. Broker hardening (MQTTS, per-device ACLs), backups, and key-rotation procedures are covered in the [ops runbook](iot-server-design/documents/iot-platform-ops-runbook.md).

## Project layout

```
src/main/java/com/huylq/iotprojectserver/
├── api/        # REST controllers, DTOs, RFC 9457 error handling
├── security/   # JWT, RBAC, device credentials/tokens, denylist, detection
├── mqtt/       # Paho client lifecycle, topic listeners, command dispatcher
├── registry/   # device registry, lifecycle, sensors
├── telemetry/  # ingest funnel (MQTT + HTTP), history, current state
├── rules/      # rule DSL parser, async evaluation worker
├── command/    # command lifecycle, parameter whitelist, timeout sweeper
├── alert/      # alert raise/acknowledge/resolve
├── health/     # heartbeat, presence (LWT), staleness sweep
├── audit/      # append-only audit writer + query API
└── common/     # pagination, rate limiting, idempotency, partitioning, seed data
src/main/resources/db/migration/   # Flyway (V1 schema, V2 technician role, V3 actuator state)
src/main/docker/compose/           # local dev infra (Postgres, Mosquitto, Redis)
iot-server-design/documents/       # design docs, OpenAPI spec, integration guide, runbook
```
