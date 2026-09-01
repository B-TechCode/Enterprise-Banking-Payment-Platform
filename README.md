# Enterprise Banking & Payment Platform

A production-oriented digital banking and bill-payment platform built with Spring Boot
microservices, Kafka, PostgreSQL and Auth0.

The platform models how a bank handles customer onboarding, account management, fund
holds, bill payment orchestration, batch settlement and retry/dead-letter handling —
using an event-driven architecture with centralized configuration and service discovery.

---

## Architecture

```text
CLIENT
  |
  v
API GATEWAY :8080  -- Auth0 RS256 validation, lb:// routing via Eureka
  |
  |-- AUTH USER            :8094   Auth0 Management API proxy
  |-- CUSTOMER SERVICE     :8083   customerdb
  |-- ACCOUNT SERVICE      :8084   accountsdb        holds, postings, ledger
  |-- BILLER SERVICE       :8088   billerdb          biller registry
  |-- PAYMENT ORCHESTRATOR :8086   paymentdb         outbox, idempotency, saga state
  |-- BILLPAY WORKER       :8090   billpayworkerdb   batching + Central1 mock
  |-- SETTLEMENT SERVICE   :8087   settlementdb      Kafka-only, no HTTP API

PLATFORM
  |-- CONFIG SERVER        :8888   centralized configuration (native, config-repo)
  |-- SERVICE REGISTRY     :8761   Eureka service discovery

INFRASTRUCTURE (Docker)
  PostgreSQL 16  :5433 -> 5432     Redis :6379    Kafka :9092    Zookeeper :2181
  Prometheus     :9090             Grafana :3000  Zipkin :9411
```

> **Note on port 5433.** The PostgreSQL container is published on host port **5433**,
> not 5432. Development machines often already run a native PostgreSQL on 5432; when
> that happens the Docker mapping is silently shadowed and services connect to the
> wrong server. Publishing on 5433 keeps the two independent.

### Services and databases

| Service | Port | Database | Notes |
|---|---|---|---|
| Config Server | 8888 | — | Serves `backend/Config-Server/config-repo`; must start first |
| Service Registry (Eureka) | 8761 | — | All services register here |
| API Gateway | 8080 | — | Auth0 JWT enforcement, Swagger UI aggregation |
| AuthUser | 8094 | — | No persistence; wraps the Auth0 Management API |
| Customer Service | 8083 | `customerdb` | Onboarding, KYC status |
| Account Service | 8084 | `accountsdb` | Accounts, holds, transactions, optimistic locking |
| Biller Service | 8088 | `billerdb` | Biller registry and activity checks |
| Payment Orchestrator | 8086 | `paymentdb` | Payment state machine, transactional outbox |
| BillPay Worker | 8090 | `billpayworkerdb` | Batching, Central1 mock endpoint |
| Settlement Service | 8087 | `settlementdb` | Kafka consumer only — no REST API |

---

## Technology stack

- **Java 21** (the reactor targets 21 and builds on newer JDKs)
- **Spring Boot 3.3.1**, **Spring Cloud 2023.0.1**
- Spring Cloud Gateway (WebFlux), Netflix Eureka, Spring Cloud Config
- Spring Security OAuth2 Resource Server, Auth0 (RS256 / JWKS)
- Spring Data JPA + Hibernate, PostgreSQL 16
- Spring Kafka (Confluent 7.6.1), Redis 7.2
- OpenFeign, Resilience4j, MapStruct, Lombok
- springdoc-openapi 2.6.0, Micrometer + Prometheus, Grafana, Zipkin
- Docker Compose, Maven multi-module reactor

---

## Prerequisites

- JDK 21 or newer
- Maven 3.9+
- Docker Desktop
- An Auth0 tenant with a custom API whose audience is `https://mockbank/api`, and a
  Machine-to-Machine application granted the scopes listed below

### Environment variables

Secrets are never committed. Provide them via the environment:

| Variable | Purpose | Default |
|---|---|---|
| `AUTH0_DOMAIN` | Auth0 tenant base URL for Management API calls | *(empty)* |
| `AUTH0_MGMT_CLIENT_ID` | Management API M2M client id | *(empty)* |
| `AUTH0_MGMT_CLIENT_SECRET` | Management API M2M client secret | *(empty)* |
| `AUTH0_MGMT_AUDIENCE` | e.g. `https://<tenant>/api/v2/` | *(empty)* |
| `AUTH0_AUDIENCE` | API audience | `https://mockbank/api` |
| `DIGITALBANK_DB_USER` | Datasource user | `postgres` |
| `DIGITALBANK_DB_PASSWORD` | Datasource password | `postgres` |

The Auth0 Management variables default to empty deliberately: services start normally
without them and only the Management API call path degrades, instead of failing at
startup on an unresolvable placeholder.

Datasource variables are prefixed `DIGITALBANK_` so they cannot be shadowed by a
generic `DB_PASSWORD` belonging to an unrelated project on the same machine.

### API scopes

`fdx:accounts.read`, `fdx:accounts.write`, `fdx:transactions.read`,
`fdx:bill.read`, `fdx:bill.write`, `admin:accounts.read`

---

## Running the platform

### 1. Start infrastructure

```powershell
cd D:\BankingPayment-MicroService\backend\infrastructure
docker compose up -d
```

> Never use `docker compose down -v`. The `-v` flag deletes the named volumes,
> including `digital-banking-platform_postgres_data`, and destroys all database state.

### 2. Create the databases (first run only)

```powershell
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE customerdb;"
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE accountsdb;"
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE billerdb;"
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE paymentdb;"
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE billpayworkerdb;"
docker exec digitalbank-postgres psql -U postgres -c "CREATE DATABASE settlementdb;"
```

Schemas are created automatically by Hibernate (`ddl-auto: update`) on first start.

### 3. Create Kafka topics (first run only)

```powershell
docker exec digitalbank-kafka kafka-topics --bootstrap-server localhost:9092 --create --topic billpay.requested --partitions 3 --replication-factor 1
docker exec digitalbank-kafka kafka-topics --bootstrap-server localhost:9092 --create --topic bill.batch.resubmit --partitions 3 --replication-factor 1
```

The remaining topics are auto-created on first publish.

### 4. Build

```powershell
cd D:\BankingPayment-MicroService
mvn clean install -DskipTests
```

> Stop any running services first — a running JVM holds its jar open and `clean` fails
> on Windows.

### 5. Start services in dependency order

**Config Server must be fully up before anything else.** Its `search-locations` is a
relative path, so it must be started from its own directory.

| Terminal | Working directory | Command |
|---|---|---|
| 1 | `backend\Config-Server` | `mvn spring-boot:run` |
| 2 | `backend\Service-Registry` | `mvn spring-boot:run` |
| 3 | `backend\API-Gateway` | `mvn spring-boot:run` |
| 4 | `backend\AuthUser-develop` | `mvn spring-boot:run` |
| 5 | `backend\CustomerService-develop` | `mvn spring-boot:run` |
| 6 | `backend\AccountService-develop` | `mvn spring-boot:run` |
| 7 | `backend\BillerService-develop` | `mvn spring-boot:run` |
| 8 | `backend\PaymentOrchestrator-develop` | `mvn spring-boot:run` |
| 9 | `backend\BillPayWorkerService-develop` | `mvn spring-boot:run` |
| 10 | `backend\SettlementService-develop` | `mvn spring-boot:run` |

Wait for ports 8888 and 8761 to be listening before starting terminal 3 onwards.

### 6. Verify

```powershell
# health
curl http://localhost:8080/actuator/health

# service discovery - expect 9 registrations
curl -H "Accept: application/json" http://localhost:8761/eureka/apps

# API documentation
start http://localhost:8080/swagger-ui.html

# metrics - expect 8 healthy targets
start http://localhost:9090/targets
```

---

## Event-driven flow

```text
POST /api/v1/payments/billpay   (Idempotency-Key required)
  |
  |-- idempotency check -> replay returns the original payment
  |-- biller validation (Feign -> Biller Service, JWT relayed)
  |-- place hold        (Feign -> Account Service, JWT relayed)
  |-- persist Payment (FUNDS_HELD) + Outbox row in ONE transaction
  v
Kafka  billpay.requested
  v
BillPay Worker  -> batch + batch lines -> Kafka  bill.batch.ready
  v
Settlement Service -> pain.001 file -> Central1 upload
  |
  |-- success -> Kafka bill.batch.submitted -> payment SUBMITTED
  |              Central1 pain.002 -> Kafka billpay.status -> payment POSTED
  |              hold released, account debited
  |
  |-- failure -> retry (max 3, counter persisted) -> Kafka bill.batch.retry
                 exhausted -> Kafka bill.batch.dlq
```

### Kafka topics

| Topic | Producer | Consumer |
|---|---|---|
| `billpay.requested` | Payment Orchestrator (outbox) | BillPay Worker |
| `billpay.enqueued` | BillPay Worker | Payment Orchestrator |
| `bill.batch.ready` | BillPay Worker | Settlement Service |
| `bill.batch.submitted` | Settlement Service | Payment Orchestrator |
| `central1.pain002` | Central1 mock | Settlement Service |
| `billpay.status` | Settlement Service | Payment Orchestrator |
| `bill.batch.retry` | Settlement Service | Settlement Service |
| `bill.batch.dlq` | Settlement Service | *(none — see Known Limitations)* |
| `bill.batch.resubmit` | *(none)* | *(none — reserved)* |

---

## Design patterns

- **Transactional Outbox** — the payment row and its event are written in one
  transaction; a background publisher ships the event to Kafka
- **Idempotency** — `Idempotency-Key` on payment initiation; unique constraint on
  `(accountId, requestFingerprint)` for account transactions
- **Consumer de-duplication** — `processed_events` keyed on `(handler, event_id)`
- **Optimistic locking** — `@Version` on accounts
- **Retry with bounded escalation** — retry counter persisted; DLQ after `MAX_RETRIES`
- **Token relay** — the caller JWT is forwarded on service-to-service Feign calls
- **Correlation IDs** — `X-Correlation-ID` propagated across HTTP and Feign

---

## Verified working

The following were verified end to end against running services and inspected directly
in PostgreSQL and Kafka.

**Platform**

- All 10 services start and report `UP`; all 6 JPA services report `db: UP`
- 9 services register with Eureka
- Centralized configuration served for every application
- OpenAPI available for all 6 services exposing an HTTP API; Swagger UI loads
- Prometheus scrapes 8 of 8 targets; JVM metrics ingested and labelled per application

**Business flow** — customer, biller and account created through the public APIs,
account funded, payment initiated and driven to `POSTED`:

| Stage | Evidence |
|---|---|
| Payment accepted | `202`, state `FUNDS_HELD` |
| Outbox published | outbox row `PENDING` -> `PUBLISHED` |
| Worker batching | batch and batch line rows created |
| Settlement | `SUBMITTED` with a Central1 reference |
| Completion | payment `POSTED` after pain.002 |
| Ledger | `CREDIT` -> `HOLD_PLACED` -> `HOLD_RELEASED` -> `DEBIT`, hold `RELEASED` |
| Kafka | exactly one new message on each of the 6 pipeline topics |

**Reliability**

- **Idempotency** — replaying a payment with the same key returned the original
  `paymentId` and created no duplicate payment, hold, transaction, outbox row or Kafka
  message
- **Retry and DLQ** — with a deliberately failing Central1 upload, the batch retried
  exactly 3 times, persisted `retry_count = 3`, transitioned to `FAILED` and escalated
  to `bill.batch.dlq` with the failure reason. Normal operation resumed once the fault
  was reverted
- **Security** — unauthenticated requests are rejected with `401`; a client-credentials
  token is admitted by the account ownership check on grant type, not on a scope that
  user tokens also hold

---

## Known limitations

These are known, deliberate gaps rather than hidden defects.

### 1. No compensation for dead-lettered payments

When a batch exhausts its retries and lands on `bill.batch.dlq`, nothing consumes that
topic. The payment remains in `BATCHED` and **the account hold is never released**, so
customer funds stay reserved indefinitely. The balance still reads the pre-payment
amount while part of it is unavailable.

The retry and DLQ mechanism itself is correct — it stops the loop and captures the
failure with enough context to triage. What is missing is the compensating action: a
DLQ consumer that releases the hold and moves the payment to `FAILED`. This is the most
significant open item and should be addressed before any production-like use.

### 2. Pre-existing test stubs fail

Four generated `contextLoads()` placeholders remain from earlier scaffolding and are
deliberately left untouched:

| Module | Issue |
|---|---|
| CustomerService | context load fails |
| PaymentOrchestrator | declared in `com.example.demo`; no `@SpringBootConfiguration` found |
| BillerService | declared in `com.example.demo`; same problem |
| AuthUser | passes, but performs a full context load taking about 17 seconds |

As a result `mvn test` fails at the reactor level and builds use `-DskipTests`. The
hand-written unit tests run cleanly on their own:

```powershell
mvn test -pl backend/commons-security-develop,backend/SettlementService-develop,backend/PaymentOrchestrator-develop
```

### 3. No automated load or scale testing

Throughput, latency and concurrency limits are unmeasured. No load test, soak test or
Kafka partition-scaling exercise has been run. Connection-pool sizing, consumer
concurrency and batch thresholds are at defaults and untuned.

### 4. Single instance per service — no high availability

Every service runs as a single local JVM. There is no clustering, replication or
failover. Kafka runs with a single broker and `replication-factor 1`, so broker loss
means data loss. Eureka self-preservation is disabled. The platform is a functional
reference, not a highly available deployment.

### 5. `application-prod.yml` Auth0 key mismatch

`AuthUser-develop/src/main/resources/application-prod.yml` declares flat keys
`auth0.mgmt-client-id` and `auth0.mgmt-client-secret`, but `ManagementTokenService`
reads the nested `auth0.mgmt.client-id` and `auth0.mgmt.client-secret`. Nothing reads
the flat keys, so activating the `prod` profile would fail at startup on an
unresolvable placeholder — the same class of failure that previously kept AuthUser off
port 8094 under the default profile. The `dev` path is correct; `prod` is untested and
needs this reconciled before use.

---

## Project structure

```text
BankingPayment-MicroService
|-- backend
|   |-- Config-Server                   centralized configuration + config-repo
|   |-- Service-Registry                Eureka
|   |-- API-Gateway                     routing, Auth0 enforcement, Swagger aggregation
|   |-- AuthUser-develop
|   |-- CustomerService-develop
|   |-- AccountService-develop
|   |-- BillerService-develop
|   |-- PaymentOrchestrator-develop
|   |-- BillPayWorkerService-develop
|   |-- SettlementService-develop
|   |-- commons-dto-develop             shared DTOs, events, exceptions
|   |-- commons-security-develop        resource-server config, JWT conversion, token relay
|   |-- commons-observability-develop   correlation IDs, access logging, metrics
|   |-- infrastructure                  docker-compose.yml, prometheus.yml
|-- scripts                             start-all.ps1
|-- pom.xml                             Maven reactor
```

---

## Author

**Aakash Chaurasiya**

Java Full Stack Developer | Backend & Scalable Systems | Exploring Agentic AI
