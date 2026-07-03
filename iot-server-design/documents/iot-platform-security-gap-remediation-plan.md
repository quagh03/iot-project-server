# Security Gap Remediation — Implementation Plan

**Purpose:** a phase-by-phase build plan to close every gap identified in `iot-platform-security-implementation.md` §18. Written in the same style as `iot-platform-implementation-plan.md` so it can be picked up and executed the same way.

**How to use this plan:** phases are ordered by the severity ranking already established in the security doc's gap table (§18) — safety-critical first, infra-hardening last. Each phase lists **Goal · Current gap · Design · Code/config changes (file-level) · Tests · DoD · Dependencies**. Phases 1–2 are pure application code and should be done first regardless of infra readiness; phases 3–8 mix app code with broker/ops config and may need infra decisions (which broker, which secrets manager) before implementation starts — those decisions are called out explicitly as **Open question** blocks, mirroring how the original implementation plan handled open questions.

**Non-negotiable invariants carried over from the base implementation plan** (do not violate while doing this work):
- No persistence detail on the wire; camelCase JSON; RFC 9457 errors; module boundaries via published interfaces only.
- Every new security-relevant action gets an `AuditEvent` code.
- New config gets a sane default so `local`/`test` profiles need zero setup.

---

## Priority order & severity mapping

| Phase | Closes gap # (from §18) | Severity | Type |
|---|---|---|---|
| 1 | #1 Safety interlock | 🔴 High | Pure app code |
| 2 | #10 Denylist validator ordering | ℹ️ Trivial | Pure app code (do alongside Phase 1 — 10 min) |
| 3 | #2 Broker ACLs | 🔴 High | App code + broker config, **needs an infra decision** |
| 4 | #3 MQTT/REST TLS | 🟠 Medium | App code (guardrails) + infra config |
| 5 | #4 Secrets custody (KMS) | 🟡 Low-medium | App dependency + config, **needs an infra decision** |
| 6 | #5 Least-privilege DB role | 🟡 Low-medium | SQL script + config split, near-zero app code |
| 7 | #6 Encryption at rest | 🟡 Low-medium | Mostly infra; one DB-connection code change |
| 8 | #7 Occupancy data classification | 🟡 Low | Pure app code |
| 9 | #9 CI security gates | ❓ Unverified | Build/CI config, not application code |

Phase 8's stale-replay item (#8 in the gap table) is **not a gap** — the code already matches the design's literal wording ("flag") — so no phase is dedicated to it; Phase 1 optionally adds a config-gated reject mode as a stretch item, noted where relevant.

---

## Phase 1 — Real safety interlock

**Goal:** replace the `NoOpSafetyInterlockCheck` stub with a real implementation so a manual command that contradicts an active safety condition is actually rejected with `409 safety-interlock`, per design doc §7.

**Current gap:** `command/NoOpSafetyInterlockCheck.violatesActiveSafety` always returns `false`. The `409`/override/audit contract around it is fully built and tested — only the signal is missing.

**Design:** the simplest signal that faithfully matches the design's own example ("a smoke rule holds it ON, **or** an OPEN smoke alert exists for that zone") is: *an OPEN alert of a safety-linked type exists for the target's zone, and the incoming command would de-escalate a safety actuator.* This needs no new "is this rule still active" bookkeeping — an open alert already *is* the durable signal that a hazard condition is unresolved, which is exactly the case where a manual command should not be able to quietly walk the actuator back.

The `command` module does not currently depend on `alert`. Add a **narrow published interface** in `alert` (module boundary rule: cross-module reads only through interfaces, never repositories) rather than having `command` reach into `AlertRepository` directly.

**Code changes:**

1. **`alert/OpenAlertQuery.java`** (new interface, published by the `alert` module):
   ```java
   public interface OpenAlertQuery {
     /** True if an OPEN alert of any of the given types exists for the zone. */
     boolean existsOpenAlert(String zone, Collection<String> types);
   }
   ```
   Implemented by the existing `AlertServiceImpl` (it already owns `alerts` reads/writes) — add the method there, backed by a new derived query on `AlertRepository`:
   ```java
   // alert/AlertRepository.java — add:
   boolean existsByZoneAndTypeInAndStatus(String zone, Collection<String> types, Alert.Status status);
   ```

2. **`command/SafetyInterlockProperties.java`** (new `@ConfigurationProperties("iot.command.safety-interlock")` record):
   ```java
   public record SafetyInterlockProperties(boolean enabled, Map<String, List<String>> alertTypesByDeviceType) {
     public SafetyInterlockProperties {
       if (alertTypesByDeviceType == null) alertTypesByDeviceType = Map.of("exhst_fan", List.of("SMOKE"));
     }
     public List<String> alertTypesFor(String deviceType) {
       return alertTypesByDeviceType.getOrDefault(deviceType, List.of());
     }
   }
   ```
   `enabled` defaults to `true` — matches the existing `matchIfMissing = true` idiom used for `iot.redis.enabled`.

3. **Extract the de-escalation check** currently private in `CommandServiceImpl` into a shared, testable utility — both the authorization gate and the new interlock need it:
   ```java
   // command/ActuatorStates.java (new, package-private-friendly final class)
   final class ActuatorStates {
     private ActuatorStates() {}
     static boolean isDeEscalating(String desiredState) {
       return desiredState != null
           && Set.of("OFF", "STOP", "CLOSED").contains(desiredState.toUpperCase());
     }
   }
   ```
   Update `CommandServiceImpl.authorizeRoleAndActuatorClass` to call `ActuatorStates.isDeEscalating(...)` and delete the old private method.

4. **`command/AlertBasedSafetyInterlockCheck.java`** (new, replaces `NoOpSafetyInterlockCheck` as the active bean):
   ```java
   @Component
   @ConditionalOnProperty(name = "iot.command.safety-interlock.enabled", havingValue = "true", matchIfMissing = true)
   @RequiredArgsConstructor
   class AlertBasedSafetyInterlockCheck implements SafetyInterlockCheck {
     private final OpenAlertQuery openAlerts;
     private final SafetyInterlockProperties props;

     @Override
     public boolean violatesActiveSafety(String targetDeviceId, String zone, String deviceType, String desiredState) {
       if (!ActuatorStates.isDeEscalating(desiredState)) return false;
       List<String> alertTypes = props.alertTypesFor(deviceType);
       return !alertTypes.isEmpty() && openAlerts.existsOpenAlert(zone, alertTypes);
     }
   }
   ```
   Gate `NoOpSafetyInterlockCheck` with `@ConditionalOnProperty(..., havingValue = "false")` so it remains available as an explicit opt-out for isolated unit tests, mirroring how `InMemoryTokenDenylist`/`RedisTokenDenylist` coexist.

5. **Widen the `SafetyInterlockCheck` interface** (breaking change, one call site) — the current `(targetDeviceId, action, parameters)` signature can't express "zone" or "the already-computed desired state" without re-deriving them. Change to:
   ```java
   boolean violatesActiveSafety(String targetDeviceId, String zone, String deviceType, String desiredState);
   ```
   Update the single call site in `CommandServiceImpl.checkSafetyInterlock`:
   ```java
   boolean held = safetyInterlock.violatesActiveSafety(
       target.getDeviceId(), target.getZone(), target.getDeviceType(), validated.desiredState());
   ```

6. **Config** (`application.yaml`, alongside the existing `iot.command` block):
   ```yaml
   iot:
     command:
       safety-interlock:
         enabled: true
         alert-types-by-device-type:
           exhst_fan: [SMOKE]
   ```

**Tests:**
- `AlertBasedSafetyInterlockCheckTest` (unit, mocked `OpenAlertQuery`): open SMOKE alert + `desiredState=OFF` on `exhst_fan` → `true`; same alert + `desiredState=ON` → `false` (escalating is always allowed); no open alert → `false`; device type not in the map (e.g. `light`) → `false` regardless of alerts.
- `CommandServiceImplTest` / `CommandIT`: manual `SET status=OFF` to an exhaust fan while a SMOKE alert is `OPEN` in that zone → `409 errors/safety-interlock`; `SUPER_ADMIN` with `override=true` + `overrideReason` → succeeds, writes `SAFETY_OVERRIDE` audit; the same command once the alert is `RESOLVED` → succeeds normally.
- Existing `CommandIT` tests that substitute a mocked `SafetyInterlockCheck` bean are unaffected (they mock the interface, not this implementation) — just update their mock's method signature to match the widened interface.

**DoD:** a real open safety alert genuinely blocks a de-escalating manual command end-to-end; escalating commands and routine actuators are never affected; disabling `iot.command.safety-interlock.enabled` reverts to no-op behavior for isolated testing without touching call sites.

**Dependencies:** none — this is self-contained within `command`/`alert`, no infra decisions required. **Do this phase first.**

---

## Phase 2 — Denylist validator ordering (quick win, bundle with Phase 1)

**Goal:** make the code match the design doc's literal claim that the denylist check runs "ahead of issuer/expiry checks."

**Current gap:** `SecurityConfig.jwtDecoder` constructs `new DelegatingOAuth2TokenValidator<>(defaults, denylistValidator)` — `defaults` first. Functionally harmless (both still reject a bad token), but worth fixing since it's a one-line change with zero risk.

**Code change:** `security/SecurityConfig.java`:
```java
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(denylistValidator, defaults));
```

**Tests:** existing `DenylistJwtValidator`/JWT decoder tests should still pass unchanged (ordering doesn't change which tokens are accepted, only which failure reason surfaces first when both would fail — not asserted on today, per the review).

**DoD:** code comment/ordering now matches design prose exactly. **Effort: ~10 minutes.**

---

## Phase 3 — Broker authorization (MQTT ACLs + auth)

**Goal:** close the largest gap — give the broker itself a notion of per-device identity and per-topic ACLs, so a rogue or MITM client can no longer publish/subscribe to another device's topics regardless of what the backend's registry cross-check catches afterward.

**Current gap:** `mosquitto.conf` runs `allow_anonymous true` on every listener, no `acl_file`, no TLS. Confirmed in `iot-platform-security-implementation.md` §10.

> **Open question — pick a path before starting this phase** (mirrors the original design doc's open question #4, "Broker product & HA"):
>
> | Option | What it buys | What it costs |
> |---|---|---|
> | **A. `mosquitto-go-auth` plugin, Postgres-backed** (recommended) | Stays on Mosquitto; auth/ACL queries run directly against existing `devices`/`device_credentials` tables — **zero new app code**, just broker config + SQL. Argon2id hash format (PHC string) is natively supported by go-auth's hasher, so no re-hashing needed. | An extra native plugin to build/vendor into the Mosquitto image; SQL-in-broker-config is a new kind of artifact to review/test. |
| **B. EMQX with an HTTP auth/ACL webhook back into this app** | Cleanest architecture — broker calls an app-owned `/internal/mqtt-auth` endpoint per connect/pub/sub, so all device-identity logic stays in Java where it's already tested. | Broker migration (Mosquitto → EMQX); new internal (non-`/api/v1`) endpoint surface to secure between broker and app. |
> | **C. Static `acl_file` regenerated by the app on every credential/registry change + `mosquitto_ctrl`/SIGHUP reload** | No new broker software. | File regen + reload orchestration is another moving part; less real-time than A/B (device gets new ACLs only after a regen cycle). |
>
> This plan below is written for **Option A** since it needs the least new code and directly reuses tables this project already has; if B or C is chosen instead, the "App code" deliverables in this phase are replaced by webhook-endpoint or file-generator code respectively, but the **topic-permission semantics** (the table below) are identical regardless of which option is picked.

### Topic-permission semantics (applies to any option chosen)

| Device category | May publish | May subscribe |
|---|---|---|
| `gateway` | `iot/telemetry/{zone}/{device_id}`, `iot/heartbeat/{device_id}` | — |
| `actuator` | `iot/command_ack/{device_id}`, `iot/heartbeat/{device_id}` | `iot/command/{device_id}` |
| any, when `status != ACTIVE` | nothing | nothing |

### Deliverables — Option A (mosquitto-go-auth, Postgres backend)

1. **Docker/infra:** replace the stock `eclipse-mosquitto` image with one that bundles `mosquitto-go-auth` (either a custom Dockerfile layering the plugin onto `eclipse-mosquitto:2`, or an existing community image), configured with `auth_opt_backends postgres`.
2. **`mosquitto.conf`** rewrite:
   ```
   listener 8883
   allow_anonymous false
   cafile /mosquitto/certs/ca.crt
   certfile /mosquitto/certs/server.crt
   keyfile /mosquitto/certs/server.key

   auth_plugin /mosquitto/go-auth.so
   auth_opt_backends postgres
   auth_opt_pg_host postgres
   auth_opt_pg_port 5432
   auth_opt_pg_dbname iot
   auth_opt_pg_user ${MOSQUITTO_DB_USER}
   auth_opt_pg_password ${MOSQUITTO_DB_PASSWORD}
   auth_opt_pg_userquery SELECT client_secret_hash FROM device_credentials dc JOIN devices d ON d.device_id = dc.device_id WHERE dc.client_id = $1 AND d.status = 'ACTIVE'
   auth_opt_pg_aclquery SELECT topic FROM mqtt_acl_view WHERE client_id = $1 AND access_type >= $2
   auth_opt_hasher argon2id
   ```
   (Exact go-auth option names to be confirmed against the version vendored — this is the shape, not a copy-paste guarantee.) The plaintext `listener 1883`/`9001` blocks are removed entirely from the prod compose override; **kept, unauthenticated, in a `docker-compose.override.local.yml`** so local dev is unaffected — this preserves the README's zero-setup local dev loop.
3. **New Postgres view** `mqtt_acl_view` (Flyway migration `V4__add_mqtt_acl_view.sql`) — a read-only view, not a table, so it always reflects current registry state with no sync/regen step:
   ```sql
   CREATE VIEW mqtt_acl_view AS
   SELECT device_id AS client_id,
          'iot/telemetry/' || zone || '/' || device_id AS topic, 2 AS access_type
     FROM devices WHERE category = 'gateway' AND status = 'ACTIVE'
   UNION ALL
   SELECT device_id, 'iot/heartbeat/' || device_id, 2
     FROM devices WHERE category IN ('gateway','actuator') AND status = 'ACTIVE'
   UNION ALL
   SELECT device_id, 'iot/command_ack/' || device_id, 2
     FROM devices WHERE category = 'actuator' AND status = 'ACTIVE'
   UNION ALL
   SELECT device_id, 'iot/command/' || device_id, 1
     FROM devices WHERE category = 'actuator' AND status = 'ACTIVE';
   -- access_type: 1 = subscribe, 2 = publish (matches go-auth's Mosquitto ACL codes)
   ```
   Grace-window secret verification (`previous_secret_hash` while `grace_expires_at > now()`) needs the user-query to check both hashes — go-auth supports multiple rows/OR conditions in `pg_userquery`; adjust the query to `SELECT client_secret_hash FROM ... UNION SELECT previous_secret_hash FROM ... WHERE grace_expires_at > now()` so a just-rotated device isn't locked out of the broker mid-roll (mirrors the existing HTTP-token grace-window behavior in `DeviceTokenServiceImpl`).
4. **App code — one small addition:** a least-privilege read-only Postgres role dedicated to the broker's auth queries (`iot_mqtt_auth`, `GRANT SELECT ON mqtt_acl_view, device_credentials, devices TO iot_mqtt_auth`), created in the same script as Phase 6's DB-role work (sequence these together if convenient, otherwise this phase can create it standalone).
5. **`MqttClientLifecycle`** (app-side, minimal): update `application-prod.yaml` `iot.mqtt.broker-url` to `ssl://...:8883` and confirm the backend's own bridge account (already supported via `iot.mqtt.username`/`password`) is provisioned as a broker-side user with full pub/sub (the backend legitimately needs to see every topic) — this is a go-auth config-side grant, not a Java change.
6. **Registry lifecycle side-effect check:** `RegistryServiceImpl.decommission`/`suspend` already revoke HTTP credentials; confirm (add a test if missing) that a `SUSPENDED`/`DECOMMISSIENED` device's broker access is *also* cut off — since the ACL view and user-query both filter on `status = 'ACTIVE'`, this should be automatic with no extra code, but it needs an integration test proving it (publish attempt from a suspended device's credentials → broker-level `CONNACK` refusal, not just an app-level rejection).

**Tests:**
- New `MqttAclIT` (Testcontainers Mosquitto-with-go-auth, or a docker-compose-based test if Testcontainers module support is unavailable): a gateway's credentials can publish only to its own telemetry/heartbeat topics; publishing to another gateway's telemetry topic is refused at `CONNACK`/`PUBACK` level, not just logged; a suspended device's credentials are refused entirely; an actuator can subscribe to its own command topic but not another's.
- Grace-window: rotate a device's credential, confirm the old secret still authenticates to the broker until `grace_expires_at`, and is refused after.

**DoD:** a rogue client presenting a real device's stolen client_id/secret is the *only* way to spoof that device on MQTT now (a fundamentally different, much narrower risk than "anyone on the network can publish anything," which is today's state); TLS is mandatory on the broker; plaintext listeners exist only in the local-dev compose override.

**Dependencies:** ideally sequence after or alongside Phase 6 (DB least-privilege roles) since both touch DB role provisioning; otherwise independent of every other phase in this plan.

---

## Phase 4 — Transport security guardrails

**Goal:** give the application a positive, code-enforced signal that TLS is actually in effect, instead of relying entirely on infrastructure the app has no visibility into.

**Current gap:** no `server.ssl.*` in any Spring profile; nothing validates the MQTT broker URL scheme; `application-prod.yaml` only sets `server.forward-headers-strategy: framework`, which is consistent with (but does not verify) a TLS-terminating reverse proxy in front of the app.

**Design:** two independent guardrails, both fail-fast in `prod` and silent/no-op in `local`/`test`:

1. **MQTT scheme guard** — `mqtt/MqttClientLifecycle` (or a small dedicated validator run at startup):
   ```java
   @PostConstruct
   void validateTransportSecurity() {
     if (activeProfileIsProd() && !props.brokerUrl().startsWith("ssl://")) {
       throw new IllegalStateException(
           "iot.mqtt.broker-url must use ssl:// in the prod profile — got: " + props.brokerUrl());
     }
   }
   ```
   Implement `activeProfileIsProd()` via an injected `Environment` (`env.matchesProfiles("prod")`), not a hardcoded string check elsewhere in the class.

2. **REST TLS posture check** — a small `ApplicationRunner` (`security/TlsPostureCheck.java`), `prod`-profile-only:
   ```java
   @Component
   @Profile("prod")
   @RequiredArgsConstructor
   class TlsPostureCheck implements ApplicationRunner {
     private final Environment env;
     public void run(ApplicationArguments args) {
       boolean appTerminatesTls = env.getProperty("server.ssl.enabled", Boolean.class, false);
       boolean proxyAsserted = env.getProperty("iot.security.trusted-proxy-tls-termination", Boolean.class, false);
       if (!appTerminatesTls && !proxyAsserted) {
         log.error("SECURITY: neither server.ssl.enabled nor iot.security.trusted-proxy-tls-termination " +
             "is set in the prod profile — this deployment may be serving plaintext HTTP. " +
             "If a reverse proxy terminates TLS, set iot.security.trusted-proxy-tls-termination=true explicitly.");
         if (env.getProperty("iot.security.fail-fast-on-missing-tls", Boolean.class, true)) {
           throw new IllegalStateException("No TLS posture asserted — refusing to start in prod. " +
               "See TlsPostureCheck for how to override.");
         }
       }
     }
   }
   ```
   This turns "we assume infra handles TLS" into an explicit, auditable configuration statement (`iot.security.trusted-proxy-tls-termination=true`) that someone had to consciously set — rather than TLS posture being simply unknowable from the app's own config.
3. **Optional — native TLS termination support:** document (don't require) a `server.ssl.*` block in `application-prod.yaml` behind env vars (`SERVER_SSL_ENABLED`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`) for deployments that don't have a reverse proxy — Spring Boot handles this natively with zero custom code once the properties are set.

**Tests:** `MqttClientLifecycleTest` — `ssl://` accepted in a mocked prod environment, `tcp://` throws; `TlsPostureCheckTest` — both flags unset → throws (or logs, depending on `fail-fast-on-missing-tls`); `trusted-proxy-tls-termination=true` → passes without `server.ssl.enabled`.

**DoD:** it is no longer possible to boot the `prod` profile with a plaintext MQTT broker URL without an explicit override; a prod boot with no TLS posture asserted at all fails fast by default (configurable to warn-only for environments mid-migration).

**Dependencies:** independent; can run in parallel with Phase 3.

---

## Phase 5 — Secrets custody (KMS integration)

**Goal:** move the JWT signing key and other prod secrets from raw environment variables to a real secrets-manager-backed property source, closing gap #4 without hand-rolling a KMS client.

**Current gap:** `JwtKeyManager`, DB, Redis, and MQTT credentials are all sourced via `${ENV_VAR}` placeholders in `application-prod.yaml`. No KMS SDK dependency exists anywhere in the code.

> **Open question — pick the org's actual secrets backend before starting:** the two common Spring-native options are **Spring Cloud Vault** (HashiCorp Vault) and **Spring Cloud AWS Secrets Manager Config** (if already on AWS). Both work the same way architecturally: they become an additional Spring `PropertySource` resolved at boot, before `@ConfigurationProperties` binding happens — so **`JwtKeyManager`, `JwtKeyProperties`, `MqttProperties`, and every other `@ConfigurationProperties` class need zero code changes.** Only the bootstrap config and the Gradle dependency change. This plan assumes Vault; swap the dependency/bootstrap block for AWS Secrets Manager Config if that's the actual target.

**Deliverables:**
1. **`build.gradle`**: add `implementation 'org.springframework.cloud:spring-cloud-starter-vault-config'` (version-managed via a Spring Cloud BOM), scoped so it's only meaningfully active when `spring.cloud.vault.*` config is present — Spring Cloud Vault degrades gracefully (no-ops) when its config is absent, so `local`/`test` profiles are unaffected without needing a profile-exclusion trick.
2. **`bootstrap.yaml`** (new, or `spring.config.import` in `application-prod.yaml` — Spring Boot 3+/4+ style):
   ```yaml
   spring:
     config:
       import: "vault://"
     cloud:
       vault:
         uri: ${VAULT_ADDR}
         authentication: ${VAULT_AUTH_METHOD:approle}
         app-role:
           role-id: ${VAULT_ROLE_ID}
           secret-id: ${VAULT_SECRET_ID}
         kv:
           enabled: true
           backend: secret
           default-context: iot-project-server
   ```
   `${JWT_ACTIVE_PRIVATE_KEY_PEM}` and friends stay as the same property *names* the app already binds to (`iot.security.jwt.keys.active-private-key-pem`, etc.) — they now resolve from Vault's KV store instead of the container's environment, with no Java changes.
3. **Ops runbook update** (`iot-platform-ops-runbook.md`): document the Vault path layout (`secret/iot-project-server/jwt`, `secret/iot-project-server/db`, `secret/iot-project-server/mqtt`), the AppRole provisioning steps, and the rotation procedure (write a new value to the path; the app re-resolves it on next restart — note that Spring Cloud Vault also supports refresh-scoped beans for zero-restart rotation if that's later desired, but that's a stretch goal, not this phase's scope).

**Tests:** a `VaultIT` (Testcontainers Vault module) that boots a Vault dev-server container, seeds a fake JWT key at the expected path, and asserts the app's `JwtKeyManager` picks it up — proving the integration wiring, not re-testing `JwtKeyManager` itself (already covered).

**DoD:** in a Vault-backed prod deployment, no JWT/DB/MQTT secret exists as a plain environment variable on the container — only a short-lived AppRole credential does; `local`/`test` profiles boot exactly as before with zero Vault dependency.

**Dependencies:** independent of every other phase; purely additive.

---

## Phase 6 — Least-privilege database roles

**Goal:** stop using one DB credential for both Flyway schema migrations and everyday application reads/writes.

**Current gap:** `application-prod.yaml`'s single `spring.datasource.*` block is used for both.

**Design:** this is a **role-provisioning script**, not a Flyway migration — roles are cluster-level objects that differ per environment and must exist *before* Flyway's own migration user can even connect to run `V1__init_schema.sql`, so they cannot be managed by the Flyway chain itself.

**Deliverables:**
1. **`db/roles/init-roles.sql`** (new directory, run once by an operator/deploy pipeline against a fresh database, **not** picked up by Flyway's migration scan path):
   ```sql
   -- Run once per environment, by a superuser, before the app's first boot.
   CREATE ROLE iot_migrator LOGIN PASSWORD :'migrator_password';
   CREATE ROLE iot_app LOGIN PASSWORD :'app_password';

   GRANT CREATE, USAGE ON SCHEMA public TO iot_migrator;
   GRANT USAGE ON SCHEMA public TO iot_app;

   -- Runtime app role: DML only, no DDL, no CREATE.
   GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO iot_app;
   GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO iot_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE iot_migrator IN SCHEMA public
     GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO iot_app;
   ALTER DEFAULT PRIVILEGES FOR ROLE iot_migrator IN SCHEMA public
     GRANT USAGE, SELECT ON SEQUENCES TO iot_app;
   ```
   The `ALTER DEFAULT PRIVILEGES ... FOR ROLE iot_migrator` clause is what makes this durable across future migrations — every table Flyway creates from then on automatically grants `iot_app` its DML rights, with no per-migration grant statement needed.
2. **`application-prod.yaml`** — split the datasource Flyway already supports natively:
   ```yaml
   spring:
     datasource:
       url: ${DB_URL}
       username: ${DB_USERNAME}       # iot_app — used by JPA/Hikari at runtime
       password: ${DB_PASSWORD}
     flyway:
       url: ${DB_URL}
       user: ${FLYWAY_DB_USERNAME}    # iot_migrator — used only during migration, at boot
       password: ${FLYWAY_DB_PASSWORD}
   ```
   Spring Boot's Flyway auto-configuration already supports distinct `spring.flyway.user`/`password` separate from `spring.datasource.*` — **zero Java code change** required.
3. **`local`/`test` profiles are untouched** — they keep the single `postgres` superuser for zero-setup dev convenience; this split is prod-only.

**Tests:** a `LeastPrivilegeRoleIT` (Testcontainers Postgres) that runs `init-roles.sql`, then Flyway migrations as `iot_migrator`, then attempts a DDL statement (`CREATE TABLE`) as `iot_app` and asserts it's rejected with a permissions error, while a normal `INSERT`/`SELECT` as `iot_app` succeeds.

**DoD:** the running application, if compromised via SQL injection or a dependency vulnerability, cannot `ALTER`/`DROP`/`CREATE` any table — it can only manipulate data through the DML it's granted, containing the blast radius of an app-level compromise to data, not schema.

**Dependencies:** none; pairs naturally with Phase 3's `iot_mqtt_auth` read-only role if done together, but not required.

---

## Phase 7 — Encryption at rest

**Goal:** close what's actually a code-addressable piece of "encryption at rest" — TLS on the app↔Postgres connection — and clearly hand off the rest (volume/disk encryption) to infrastructure, rather than leaving it unaddressed.

**Current gap:** `pgcrypto` is installed only for `gen_random_uuid()`; there is no TLS on the JDBC connection and no volume-level encryption configured anywhere in this repo.

**Scope decision:** column-level encryption is **not** proposed for telemetry or any hot-path table — rule evaluation and dashboard queries need to compare/aggregate `value_num`/`value_bool` directly, and encrypting those columns would break every rule condition (`office_1.temp > 30`) without decrypting server-side first, which defeats the purpose. Nothing else in the schema is currently plaintext-and-sensitive (passwords and client secrets are already hashed, which is strictly stronger than reversible encryption for credentials). So this phase's *code* deliverable is narrow; disk/volume encryption remains an ops runbook item.

**Deliverables:**
1. **JDBC connection TLS** — `application-prod.yaml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=require
   ```
   `sslmode=require` (or `verify-full` with a supplied `sslrootcert` if the org's Postgres exposes a CA-signed cert) encrypts data-in-transit between the app and the database — this is a one-line config change, no Java code.
2. **Redis TLS** — already scaffolded (`REDIS_TLS_ENABLED` exists in `application-prod.yaml` per the security review); confirm it's actually wired to `spring.data.redis.ssl.enabled` and add a test asserting the property binds where expected if it doesn't already.
3. **Ops runbook update** (`iot-platform-ops-runbook.md`): add an explicit "encryption at rest" section pointing at the actual mechanism the deployment target provides (e.g., RDS storage encryption, EBS-encrypted volumes, or the cloud provider's managed-Postgres-at-rest option) — this is infrastructure configuration outside this codebase's reach, and the runbook should say so plainly rather than implying application code handles it.

**Tests:** a connection-string assertion test (or a `@SpringBootTest` context-load check with a Testcontainers Postgres configured for SSL) confirming the app successfully connects with `sslmode=require` set.

**DoD:** data in transit between the app and both Postgres and Redis is encrypted; the ops runbook has a concrete, non-aspirational answer for "what encrypts data at rest" naming the actual infra mechanism instead of an unimplemented `pgcrypto` reference.

**Dependencies:** independent.

---

## Phase 8 — Occupancy / privacy-sensitive data classification

**Goal:** give the OWASP-IoT-mapping claim ("occupancy data treated as sensitive") an actual, observable implementation, without breaking the existing VIEWER-can-read-all-telemetry contract described in the API docs.

**Current gap:** zero code or schema differentiation for occupancy-adjacent sensor types (motion, light/PIR-style presence sensors) — everything is stored and access-controlled identically to a temperature reading.

**Design:** the least invasive approach that still produces a real, checkable control is: **(a)** a config-driven classification list, **(b)** an audit trail specifically for reads that touch classified sensor types (so "who looked at occupancy data and when" becomes answerable, satisfying the non-repudiation half of privacy handling), and **(c)** tagging the wire response so a frontend *could* choose to gate/blur this data in the UI later without a backend contract change. Access-role restriction (e.g. requiring `OPERATOR`+ instead of `VIEWER`) is **not** proposed here since it would be a breaking API change to an already-documented contract — flagged as a follow-up decision for whoever owns the product requirement, not bundled into this phase.

**Deliverables:**
1. **`telemetry/PrivacyClassificationProperties.java`** (new `@ConfigurationProperties("iot.telemetry.privacy")`):
   ```java
   public record PrivacyClassificationProperties(List<String> sensitiveSensorTypes) {
     public PrivacyClassificationProperties {
       if (sensitiveSensorTypes == null) sensitiveSensorTypes = List.of("motion", "occupancy", "light");
     }
   }
   ```
   ```yaml
   iot:
     telemetry:
       privacy:
         sensitive-sensor-types: [motion, occupancy, light]
   ```
2. **Audit on classified reads** — in `TelemetryServiceImpl.queryHistory` (the `GET /telemetry` path) and `currentState`/`currentStateByZoneAndType`, after resolving which sensor types are being returned, if any intersect `sensitiveSensorTypes`, write a `PRIVACY_SENSITIVE_QUERY` audit entry (new `AuditEvent` code) with the caller and the queried scope (sensorId/zone) as `detail`. This is a read-path audit call, `REQUIRES_NEW` like every other audit write, so it never affects the transaction it's attached to.
3. **Response tagging** — add `sensitive: boolean` to `TelemetryReadingDto`/`CurrentStateDto` (computed, not stored), server-set based on the same classification list — purely additive per the API's own evolution rules (new optional field, no existing client breaks).
4. **New `AuditEvent` code:** `PRIVACY_SENSITIVE_QUERY` (category: telemetry/privacy).

**Tests:** a query touching `sensorType=motion` writes the new audit event with the correct actor/detail; a query touching only `temp`/`hmid` does not; the DTO tagging is correct for both classified and unclassified readings; existing `VIEWER` read access is unchanged (no new `@PreAuthorize` added).

**DoD:** every read of occupancy-adjacent telemetry is now individually auditable by actor and time; the classification list is a one-line config change to extend; the FE has a `sensitive` flag available if/when a UI-level redaction decision is made — all without changing who can read what today.

**Dependencies:** independent.

---

## Phase 9 — CI security gates

**Goal:** close the "not verified in this pass" gap — add the three gate types the design doc's security checklist calls for: SCA, SAST, secret scanning.

**Current gap:** unconfirmed either way in the code review (out of scope of `src/main`/`resources`); the implementation plan's own Phase 10 notes explicitly marked this "out of scope this pass."

**Deliverables** (build/CI config, not application source):
1. **SCA** — add the OWASP Dependency-Check Gradle plugin:
   ```gradle
   plugins {
     id 'org.owasp.dependencycheck' version '<latest>'
   }
   dependencyCheck {
     failBuildOnCVSS = 7.0
     suppressionFile = 'config/dependency-check-suppressions.xml'
   }
   ```
   Wire `./gradlew dependencyCheckAnalyze` into the CI pipeline as a required check.
2. **SAST** — add SpotBugs + the FindSecBugs plugin (Java-native, no external service dependency):
   ```gradle
   plugins {
     id 'com.github.spotbugs' version '<latest>'
   }
   spotbugs { effort = 'max'; reportLevel = 'medium' }
   dependencies { spotbugsPlugins 'com.h3xstream.findsecbugs:findsecbugs-plugin:<latest>' }
   ```
   Alternatively (or additionally), a `semgrep ci` step in the CI workflow using the `p/java` and `p/owasp-top-ten` rulesets — lower setup cost than SpotBugs if the CI runner can pull the Semgrep container.
3. **Secret scanning** — add `gitleaks` as a CI step (and optionally a pre-commit hook):
   ```yaml
   - name: gitleaks
     uses: gitleaks/gitleaks-action@v2
   ```
4. **CI workflow wiring** (assuming GitHub Actions, adjust for whatever CI is actually in use):
   ```yaml
   jobs:
     security:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { java-version: '21', distribution: 'corretto' }
         - run: ./gradlew dependencyCheckAnalyze spotbugsMain
         - uses: gitleaks/gitleaks-action@v2
   ```
5. **Bypass policy** (document in the ops runbook, per the design checklist's own wording "document the failure-mode and bypass policy"): a documented, auditable override path (e.g., a required PR label + a named approver) for the rare case a finding is a confirmed false positive, rather than an undocumented ability to skip CI.

**Tests:** none in the application test suite — verify by intentionally introducing a known-vulnerable dependency version or a fake secret in a throwaway branch and confirming the pipeline fails, then reverting.

**DoD:** every PR to `develop`/`main` runs SCA + SAST + secret scanning and cannot merge on a high-severity finding without an explicit, logged override.

**Dependencies:** independent of every other phase; can be done at any time, including in parallel with everything above.

---

## Coverage matrix — gap → phase

| # (§18) | Gap | Phase | Fully closed by this plan? |
|---|---|---|---|
| 1 | Safety interlock is a no-op | 1 | ✅ Yes |
| 2 | No broker ACLs / anonymous MQTT | 3 | ✅ Yes (pending infra option A/B/C decision) |
| 3 | No TLS enforcement (REST/MQTT) | 4 | 🟡 Guardrails yes; actual cert provisioning is ops |
| 4 | Secrets via env var, not KMS | 5 | ✅ Yes (pending Vault vs AWS decision) |
| 5 | Single DB role for migrate + app | 6 | ✅ Yes |
| 6 | No encryption at rest | 7 | 🟡 In-transit yes; volume-at-rest is ops, documented |
| 7 | No occupancy data classification | 8 | 🟡 Auditability + tagging yes; access-restriction is a product decision, not bundled |
| 8 | Stale-replay is warn-only | — | Not a gap — matches design's literal wording; no phase needed |
| 9 | No CI security gates | 9 | ✅ Yes |
| 10 | Denylist validator ordering | 2 | ✅ Yes |

**Suggested execution order for a single team:** 1 → 2 → 9 (can start immediately, zero dependencies) → 6 → 3 (sequence after 6 to reuse DB-role work) → 4 → 8 → 5 → 7 (5 and 7 are the most infra-decision-dependent; do last or in parallel with an infra/ops owner).
