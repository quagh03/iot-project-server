# IoT Office Monitoring & Control Data Specification

## 1. Overview

This document defines the data model, communication protocols, security requirements, telemetry payloads, command payloads, and device management standards for the Office IoT Monitoring & Control Platform.

The platform is responsible for:

* Collecting telemetry data from IoT devices
* Monitoring environmental conditions
* Managing office devices and sensors
* Evaluating business rules
* Sending control commands to actuators
* Providing REST APIs for administrative and frontend applications
* Enforcing authentication and authorization for both users and devices

---

## 2. System Architecture

### Components

```text
IoT Device
    ├── HTTP REST (Telemetry Fallback)
    └── MQTT (Primary Communication)

                ↓

        MQTT Broker

                ↓

         IoT Backend

    ├── Device Registry
    ├── Authentication Service
    ├── Authorization Service
    ├── Telemetry Service
    ├── Rule Engine
    ├── Command Service
    ├── Audit Service

                ↓

          PostgreSQL

                ↓

       Frontend Dashboard
```

### Communication Model

| Component          | Protocol    |
| ------------------ | ----------- |
| Device → Server    | MQTT / HTTP |
| Server → Device    | MQTT        |
| Frontend → Backend | HTTPS REST  |
| Backend → Database | PostgreSQL  |

---

## 3. Zones

The system contains the following monitored zones:

* pantry
* storage
* prvt_meeting
* office_1
* office_2
* lobby
* connect
* director
* finance_mng
* meeting
* technical_mng
* vice_director

---

## 4. Device Categories

### Gateway Devices

Gateway devices aggregate data from sensors and communicate with the IoT Platform.

Examples:

```text
OFFICE1_NODE_01
LOBBY_NODE_01
PANTRY_NODE_01
```

### Sensors

Sensors collect environmental or status information.

Examples:

```text
OFFICE1_TEMP_01
OFFICE1_HMID_01
OFFICE1_SMKE_01
```

### Actuators

Actuators receive commands from the platform.

Examples:

```text
LIGHT_001
AC_01
EXHST_06
CURT_018
```

---

## 5. Sensor Types

### 5.1 Environmental Sensors

| Sensor Type | Description     | Unit    |
| ----------- | --------------- | ------- |
| temp        | Temperature     | °C      |
| hmid        | Humidity        | %       |
| smoke       | Smoke Detection | Boolean |

### 5.2 Door / Window Sensors

| Sensor Type | Description               | Unit    |
| ----------- | ------------------------- | ------- |
| light       | Ambient Light Level       | lux     |
| open        | Door / Window Open Status | Boolean |

---

## 6. Controllable Device Types

| Device Type | Description     |
| ----------- | --------------- |
| light       | Ceiling Light   |
| ac          | Air Conditioner |
| exhst_fan   | Exhaust Fan     |
| curtain     | Window Curtain  |

---

## 7. Device Registry

Every physical device must be registered before it can communicate with the platform.

### Device Metadata

```json
{
  "device_id": "OFFICE1_NODE_01",
  "device_type": "gateway",
  "zone": "office_1",
  "firmware_version": "1.2.0",
  "status": "ACTIVE",
  "protocols": [
    "MQTT",
    "HTTP"
  ]
}
```

### Device Status

```text
ACTIVE
INACTIVE
SUSPENDED
DECOMMISSIONED
```

---

## 8. Authentication & Authorization

### 8.1 User Authentication

Administrative users authenticate through OAuth2 and JWT.

Supported grant types:

```text
Authorization Code
Refresh Token
Client Credentials
```

### User Roles

| Role        | Description                  |
| ----------- | ---------------------------- |
| SUPER_ADMIN | Full system access           |
| ADMIN       | Manage devices, users, rules |
| OPERATOR    | Monitor and control devices  |
| VIEWER      | Read-only access             |

### Example JWT

```json
{
  "sub": "user_001",
  "username": "admin",
  "roles": [
    "ADMIN"
  ]
}
```

---

### 8.2 Device Authentication

Every device must authenticate before:

* Publishing telemetry
* Receiving commands
* Sending acknowledgements

### Authentication Method

OAuth2 Client Credentials Flow

Each device receives:

```text
client_id
client_secret
```

Example:

```json
{
  "client_id": "OFFICE1_NODE_01",
  "client_secret": "********"
}
```

The device exchanges credentials for an access token.

```http
POST /oauth2/token
```

Response:

```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

---

### Device Scopes

| Scope             | Description           |
| ----------------- | --------------------- |
| telemetry:publish | Publish telemetry     |
| command:subscribe | Receive commands      |
| command:ack       | Send acknowledgements |
| heartbeat:publish | Publish heartbeat     |

---

## 9. Security Requirements

### Transport Security

All communications must use:

```text
TLS 1.2+
HTTPS
MQTTS
```

Plain HTTP and plain MQTT must be disabled in production.

---

### Password Storage

Passwords must be stored using:

```text
Argon2id
```

or

```text
BCrypt
```

Never store plaintext passwords.

---

### API Security

All REST APIs must require:

```text
JWT Access Token
Role-Based Access Control (RBAC)
HTTPS
```

---

### Recommended Security Headers

```http
Strict-Transport-Security
X-Content-Type-Options
X-Frame-Options
Content-Security-Policy
```

---

### Token Expiration

Recommended values:

| Token Type    | Expiration |
| ------------- | ---------- |
| Access Token  | 1 hour     |
| Refresh Token | 30 days    |
| Device Token  | 1 hour     |

---

### Audit Logging

The platform must audit:

* User login
* Device registration
* Device deletion
* Rule changes
* Command execution
* Permission changes

---

## 10. MQTT Communication Model

### Telemetry Topic

```text
iot/telemetry/{zone}
```

Example:

```text
iot/telemetry/office_1
```

---

### Command Topic

```text
iot/command/{device_id}
```

Example:

```text
iot/command/OFFICE1_NODE_01
```

---

### Command Acknowledgement Topic

```text
iot/command_ack/{device_id}
```

Example:

```text
iot/command_ack/OFFICE1_NODE_01
```

---

### Heartbeat Topic

```text
iot/heartbeat/{device_id}
```

Example:

```text
iot/heartbeat/OFFICE1_NODE_01
```

---

## 11. Sensor Data Publish Payload

### Environmental Sensors

```json
{
  "timestamp": "2026-06-21T20:39:36Z",
  "zone": "office_1",
  "gateway_id": "OFFICE1_NODE_01",
  "sensors": [
    {
      "id": "OFFICE1_TEMP_01",
      "type": "temp",
      "value": 25.8,
      "unit": "C"
    },
    {
      "id": "OFFICE1_HMID_01",
      "type": "hmid",
      "value": 60.5,
      "unit": "%"
    },
    {
      "id": "OFFICE1_SMKE_01",
      "type": "smoke",
      "value": false
    }
  ]
}
```

---

## 12. Device Control Payload

### Common Command Structure

```json
{
  "command_id": "CMD_21062026_036",
  "target_id": "DEVICE_ID",
  "type": "DEVICE_TYPE",
  "action": "SET",
  "parameters": {}
}
```

---

## 13. Command Acknowledgement

Every command execution must return a result.

```json
{
  "command_id": "CMD_21062026_036",
  "device_id": "AC_01",
  "status": "SUCCESS",
  "executed_at": "2026-06-21T20:40:00Z"
}
```

### Status Values

```text
PENDING
RECEIVED
SUCCESS
FAILED
TIMEOUT
```

---

## 14. Device Heartbeat

Devices must periodically publish health information.

Recommended interval:

```text
30-60 seconds
```

### Heartbeat Payload

```json
{
  "device_id": "OFFICE1_NODE_01",
  "timestamp": "2026-06-21T20:39:36Z",
  "status": "ONLINE",
  "firmware_version": "1.2.0",
  "memory_usage_pct": 43,
  "cpu_usage_pct": 21,
  "wifi_rssi": -58
}
```

---

## 15. Rule Engine

Rules are evaluated on the server.

### Example Rule

```text
IF office_1.temp > 30
THEN AC_01.status = ON
```

### Smoke Detection Rule

```text
IF smoke == true
THEN
    EXHST_01.status = ON
    SEND_ALERT
```

### Low Light Rule

```text
IF light < 200
THEN LIGHT_001.status = ON
```

---

## 16. Naming Convention

### Sensors

```text
<ZONE>_<TYPE>_<INDEX>
```

Examples:

```text
OFFICE1_TEMP_01
OFFICE1_HMID_01
OFFICE1_SMKE_01
```

### Devices

```text
LIGHT_001
AC_01
EXHST_06
CURT_018
```

### Gateways

```text
<ZONE>_NODE_<INDEX>
```

Examples:

```text
OFFICE1_NODE_01
PANTRY_NODE_01
LOBBY_NODE_01
```

---

## 17. Supported Values

### Boolean

```json
true
false
```

### Device Status

```json
"ON"
"OFF"
```

### Curtain Direction

```json
"UP"
"DOWN"
"STOP"
```

### AC Modes

```json
"COOL"
"HEAT"
"DRY"
"FAN"
"AUTO"
```

# 18. REST API Specification

All APIs must be exposed under the following base path:

```text
/api/v1
```

All requests and responses must use:

```http
Content-Type: application/json
```

All protected endpoints require:

```http
Authorization: Bearer <access_token>
```

---

# 19. Authentication APIs

## 19.1 User Login

Authenticate an administrative user.

### Request

```http
POST /api/v1/auth/login
```

```json
{
  "username": "admin",
  "password": "********"
}
```

### Response

```json
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "expires_in": 3600,
  "token_type": "Bearer"
}
```

---

## 19.2 Refresh Token

### Request

```http
POST /api/v1/auth/refresh
```

```json
{
  "refresh_token": "eyJ..."
}
```

### Response

```json
{
  "access_token": "eyJ...",
  "expires_in": 3600
}
```

---

## 19.3 Device Token Request

Used by IoT devices to obtain an access token.

### Request

```http
POST /oauth2/token
```

```json
{
  "client_id": "OFFICE1_NODE_01",
  "client_secret": "********",
  "grant_type": "client_credentials"
}
```

### Response

```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

---

# 20. User Management APIs

## 20.1 Create User

### Request

```http
POST /api/v1/users
```

### Required Role

```text
SUPER_ADMIN
```

### Request Body

```json
{
  "username": "operator01",
  "password": "********",
  "role": "OPERATOR"
}
```

---

## 20.2 List Users

```http
GET /api/v1/users
```

---

## 20.3 Get User Details

```http
GET /api/v1/users/{user_id}
```

---

## 20.4 Update User

```http
PUT /api/v1/users/{user_id}
```

---

## 20.5 Delete User

```http
DELETE /api/v1/users/{user_id}
```

---

# 21. Device Management APIs

## 21.1 Register Device

Registers a gateway, sensor, or actuator.

### Request

```http
POST /api/v1/devices
```

### Request Body

```json
{
  "device_id": "OFFICE1_NODE_01",
  "device_type": "gateway",
  "zone": "office_1",
  "firmware_version": "1.0.0",
  "protocols": [
    "MQTT",
    "HTTP"
  ]
}
```

### Response

```json
{
  "device_id": "OFFICE1_NODE_01",
  "client_id": "OFFICE1_NODE_01",
  "client_secret": "generated-secret"
}
```

---

## 21.2 List Devices

```http
GET /api/v1/devices
```

### Query Parameters

| Parameter | Description           |
| --------- | --------------------- |
| zone      | Filter by zone        |
| type      | Filter by device type |
| status    | Filter by status      |

Example:

```http
GET /api/v1/devices?zone=office_1
```

---

## 21.3 Get Device Details

```http
GET /api/v1/devices/{device_id}
```

---

## 21.4 Update Device

```http
PUT /api/v1/devices/{device_id}
```

---

## 21.5 Delete Device

```http
DELETE /api/v1/devices/{device_id}
```

---

## 21.6 Rotate Device Credentials

Generate a new client secret.

```http
POST /api/v1/devices/{device_id}/rotate-secret
```

---

## 21.7 Suspend Device

```http
POST /api/v1/devices/{device_id}/suspend
```

---

## 21.8 Activate Device

```http
POST /api/v1/devices/{device_id}/activate
```

---

# 22. Sensor Management APIs

## 22.1 Register Sensor

```http
POST /api/v1/sensors
```

### Request Body

```json
{
  "sensor_id": "OFFICE1_TEMP_01",
  "gateway_id": "OFFICE1_NODE_01",
  "type": "temp",
  "zone": "office_1"
}
```

---

## 22.2 List Sensors

```http
GET /api/v1/sensors
```

---

## 22.3 Get Sensor Details

```http
GET /api/v1/sensors/{sensor_id}
```

---

## 22.4 Update Sensor

```http
PUT /api/v1/sensors/{sensor_id}
```

---

## 22.5 Delete Sensor

```http
DELETE /api/v1/sensors/{sensor_id}
```

---

# 23. Telemetry APIs

## 23.1 Publish Telemetry (HTTP Fallback)

Used when MQTT is unavailable.

```http
POST /api/v1/telemetry
```

### Authorization

```text
Device Access Token Required
```

### Request Body

```json
{
  "timestamp": "2026-06-21T20:39:36Z",
  "zone": "office_1",
  "gateway_id": "OFFICE1_NODE_01",
  "sensors": [
    {
      "id": "OFFICE1_TEMP_01",
      "type": "temp",
      "value": 25.8
    }
  ]
}
```

---

## 23.2 Query Telemetry

Retrieve historical telemetry.

```http
GET /api/v1/telemetry
```

### Query Parameters

| Parameter | Description     |
| --------- | --------------- |
| zone      | Zone filter     |
| sensor_id | Sensor filter   |
| from      | Start timestamp |
| to        | End timestamp   |
| page      | Page number     |
| size      | Page size       |

Example:

```http
GET /api/v1/telemetry?zone=office_1&from=2026-06-01T00:00:00Z
```

---

# 24. Command APIs

## 24.1 Send Command

Issue a control command to a device.

```http
POST /api/v1/commands
```

### Request Body

```json
{
  "target_id": "AC_01",
  "type": "ac",
  "action": "SET",
  "parameters": {
    "status": "ON",
    "set_temp": 24
  }
}
```

### Response

```json
{
  "command_id": "CMD_21062026_036",
  "status": "PENDING"
}
```

---

## 24.2 Get Command Status

```http
GET /api/v1/commands/{command_id}
```

---

## 24.3 Command History

```http
GET /api/v1/commands
```

### Query Parameters

| Parameter | Description    |
| --------- | -------------- |
| target_id | Device filter  |
| status    | Command status |
| from      | Start time     |
| to        | End time       |

---

# 25. Rule Engine APIs

## 25.1 Create Rule

```http
POST /api/v1/rules
```

### Request Body

```json
{
  "name": "Auto AC Control",
  "enabled": true,
  "condition": "office_1.temp > 30",
  "action": "AC_01.status = ON"
}
```

---

## 25.2 List Rules

```http
GET /api/v1/rules
```

---

## 25.3 Get Rule Details

```http
GET /api/v1/rules/{rule_id}
```

---

## 25.4 Update Rule

```http
PUT /api/v1/rules/{rule_id}
```

---

## 25.5 Delete Rule

```http
DELETE /api/v1/rules/{rule_id}
```

---

## 25.6 Enable Rule

```http
POST /api/v1/rules/{rule_id}/enable
```

---

## 25.7 Disable Rule

```http
POST /api/v1/rules/{rule_id}/disable
```

---

# 26. Audit APIs

## 26.1 Query Audit Logs

```http
GET /api/v1/audit-logs
```

### Query Parameters

| Parameter | Description |
| --------- | ----------- |
| actor     | User filter |
| event     | Event type  |
| from      | Start time  |
| to        | End time    |

---

# 27. Health & Monitoring APIs

## 27.1 System Health

```http
GET /actuator/health
```

### Response

```json
{
  "status": "UP"
}
```

---

## 27.2 System Metrics

```http
GET /actuator/metrics
```

---

## 27.3 Device Connectivity Status

```http
GET /api/v1/devices/{device_id}/status
```

### Response

```json
{
  "device_id": "OFFICE1_NODE_01",
  "connection_status": "ONLINE",
  "last_seen": "2026-06-21T20:39:36Z"
}
```

---

# 28. Error Response Format

All API errors must follow a consistent structure.

```json
{
  "timestamp": "2026-06-21T20:39:36Z",
  "status": 403,
  "error": "Forbidden",
  "code": "ACCESS_DENIED",
  "message": "Insufficient permissions",
  "path": "/api/v1/devices"
}
```

---

# 29. Rate Limiting

To protect the platform from abuse:

| Endpoint Type       | Limit               |
| ------------------- | ------------------- |
| User APIs           | 100 requests/minute |
| Device APIs         | 300 requests/minute |
| Authentication APIs | 20 requests/minute  |
| Telemetry APIs      | Configurable        |

Exceeding limits returns:

```http
429 Too Many Requests
```
