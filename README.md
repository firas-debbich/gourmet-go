# Gourmet-Go v2 — Complete Study Guide

> A distributed food-ordering system built with Java, gRPC, Docker, and the **Saga orchestration pattern**.  
> This guide explains every layer — from the business logic to the container runtime — so you can answer any exam question about the design.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Repository Structure](#2-repository-structure)
3. [Part I — Distributed Systems Deep Dive](#part-i--distributed-systems-deep-dive)
   - 3.1 [What Is a Distributed System?](#31-what-is-a-distributed-system)
   - 3.2 [Microservices Architecture](#32-microservices-architecture)
   - 3.3 [gRPC — What, Why, How](#33-grpc--what-why-how)
   - 3.4 [Protocol Buffers (protobuf)](#34-protocol-buffers-protobuf)
   - 3.5 [The Saga Pattern](#35-the-saga-pattern)
   - 3.6 [Service-by-Service Breakdown](#36-service-by-service-breakdown)
   - 3.7 [Data Flow — Full Request Lifecycle](#37-data-flow--full-request-lifecycle)
   - 3.8 [Distributed Consistency Trade-offs](#38-distributed-consistency-trade-offs)
4. [Part II — DevOps & SRE Deep Dive](#part-ii--devops--sre-deep-dive)
   - 4.1 [Docker — Concepts and Images](#41-docker--concepts-and-images)
   - 4.2 [Dockerfiles — Line-by-Line](#42-dockerfiles--line-by-line)
   - 4.3 [Docker Compose — Orchestrating Locally](#43-docker-compose--orchestrating-locally)
   - 4.4 [Networking in Docker](#44-networking-in-docker)
   - 4.5 [Health Checks and Startup Order](#45-health-checks-and-startup-order)
   - 4.6 [Environment Variables and Configuration](#46-environment-variables-and-configuration)
   - 4.7 [Ports — Inside vs. Outside the Container](#47-ports--inside-vs-outside-the-container)
   - 4.8 [CI/CD Pipeline with GitHub Actions](#48-cicd-pipeline-with-github-actions)
   - 4.9 [SRE Principles Applied to This System](#49-sre-principles-applied-to-this-system)
5. [How to Run the System](#5-how-to-run-the-system)
6. [Quick-Reference Cheat Sheet](#6-quick-reference-cheat-sheet)

---

## 1. System Overview

Gourmet-Go is an online food-ordering platform. A user types an **Order ID** and an **amount** in a web browser. The system then runs a multi-step distributed transaction (called a **Saga**) to decide whether the order is `APPROVED` or `REJECTED`.

```
Browser (React)
      |
      | HTTP (port 8088)
      v
 API Gateway  (Spring Boot)
      |
      | HTTP  ──────────────────────────────────┐
      v                                          |
 Order Orchestrator (Saga runner)                |
      |                                          |
      | gRPC  ──────────────────────────────┐   |
      v                             gRPC    v   v
 Order Service ◄──────────────── Kitchen   Accounting
 (port 50051)                   Service    Service
 (port 8081 HTTP)               (50052)    (50053)
      |                            |           |
      v                            v           v
  orders-db                   kitchen-db  accounting-db
  (PostgreSQL)                (PostgreSQL)(PostgreSQL)
```

**Key numbers to memorize:**

| Service           | gRPC port | HTTP port |
|-------------------|-----------|-----------|
| order-service     | 50051     | 8081      |
| kitchen-service   | 50052     | —         |
| accounting-service| 50053     | —         |
| order-orchestrator| —         | 8084      |
| api-gateway       | —         | 8080 → exposed on host as 8088 |

---

## 2. Repository Structure

```
gourmet-go-v2/
├── pom.xml                    ← Maven parent (shared dependency versions)
├── docker-compose.yml         ← Runs everything locally with one command
├── .github/workflows/
│   └── blank.yml              ← CI/CD: builds and pushes Docker images
│
├── order-service/             ← Manages order state (PENDING, APPROVED, REJECTED)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/...           ← OrderServiceServer, OrderServiceImpl, OrderRepository, OrderHttpServer
│       └── proto/order.proto  ← gRPC contract
│
├── kitchen-service/           ← Creates / rejects kitchen tickets
│   ├── Dockerfile
│   └── src/main/
│       ├── java/...           ← KitchenServiceServer, KitchenServiceImpl, KitchenRepository
│       └── proto/kitchen.proto
│
├── accounting-service/        ← Authorizes payments (amount < 100 = approved)
│   ├── Dockerfile
│   └── src/main/
│       ├── java/...           ← AccountingServiceServer, AccountingServiceImpl, AccountingRepository
│       └── proto/accounting.proto
│
├── order-orchestrator/        ← Runs the Saga (the "brain")
│   ├── Dockerfile
│   └── src/main/java/...      ← OrchestratorServer, SagaOrchestrator, SagaResult
│
├── api-gateway/               ← Single entry point for the UI (Spring Boot)
│   ├── Dockerfile
│   └── src/main/
│       ├── java/...           ← GatewayApplication, GatewayController
│       └── resources/application.yml
│
└── frontend/                  ← React UI
    ├── src/App.js
    └── package.json
```

---

# Part I — Distributed Systems Deep Dive

## 3.1 What Is a Distributed System?

A **distributed system** is a collection of independent computers (nodes) that communicate over a network and appear to users as a single coherent system.

**Why bother?**
- **Scalability**: you can scale one service without touching the others.
- **Fault isolation**: if the kitchen-service crashes, the accounting-service is unaffected.
- **Team independence**: each team owns one service and deploys it separately.

**The hard part — the CAP theorem:**
You can only guarantee two of the three at once:
- **C**onsistency — every read sees the latest write.
- **A**vailability — every request gets a response.
- **P**artition tolerance — the system survives network splits.

In Gourmet-Go we sacrifice **strong consistency** in favor of availability and partition tolerance. The Saga pattern is the mechanism that compensates for this trade-off.

---

## 3.2 Microservices Architecture

Each service in Gourmet-Go is a **microservice**: an independently deployable unit with its own:
- Source code and build artifact (`.jar`)
- Database (no shared database between services)
- Network endpoint (its own port)
- Docker image and container

**Why no shared database?** This is the golden rule of microservices. If two services share one database, they are tightly coupled — a schema change by Team A breaks Team B. Each service owns its data, and they communicate only through APIs.

| Service             | Database    | Table     | Purpose                            |
|---------------------|-------------|-----------|------------------------------------|
| order-service       | orders_db   | orders    | order_id, status                   |
| kitchen-service     | kitchen_db  | tickets   | order_id, status (CREATED/REJECTED)|
| accounting-service  | accounting_db | payments| order_id, amount, authorized       |

---

## 3.3 gRPC — What, Why, How

### What is gRPC?

**gRPC** (Google Remote Procedure Call) is a framework that lets a program call a function on a remote machine as if it were a local call. It uses:
- **HTTP/2** as the transport protocol (faster than HTTP/1.1 — multiplexed streams, header compression).
- **Protocol Buffers** (protobuf) as the serialization format (smaller and faster than JSON).
- A **code generator** that produces client and server stubs from a `.proto` file.

### Why gRPC instead of REST?

| Feature         | REST + JSON                    | gRPC + protobuf                   |
|-----------------|-------------------------------|-----------------------------------|
| Serialization   | Text (JSON) — human-readable  | Binary — ~5-10x smaller           |
| Schema          | Optional (OpenAPI)            | Mandatory (`.proto` file)         |
| Code generation | Manual or third-party         | Built-in (`protoc` compiler)      |
| Streaming       | Workarounds (SSE, WS)         | First-class (4 streaming modes)   |
| Performance     | Slower                        | Faster (HTTP/2 + binary)          |
| Error contracts | Informal                      | Typed status codes                |

gRPC is ideal for **internal service-to-service** communication where performance and type safety matter. The Orchestrator uses it to talk to order-service, kitchen-service, and accounting-service.

### How gRPC Works in This Project

**Step 1 — Write a `.proto` file (the contract):**

```protobuf
// kitchen-service/src/main/proto/kitchen.proto
syntax = "proto3";
package com.gourmet.kitchen;

service KitchenService {
  rpc CreateTicket(TicketRequest) returns (TicketResponse);
  rpc RejectTicket(RejectRequest) returns (RejectResponse);
}

message TicketRequest  { string orderId = 1; }
message TicketResponse { bool success   = 1; }

message RejectRequest  { string orderId    = 1; }
message RejectResponse { bool acknowledged = 1; }
```

The `.proto` defines:
- **Service** — the remote interface (like a Java interface).
- **rpc** — one remote method.
- **message** — the data structure (like a Java class), serialized as protobuf bytes.
- **Field numbers** (= 1, = 2) — these tag each field in the binary encoding; they must never change once deployed.

**Step 2 — Maven generates Java classes** at build time using the `protobuf-maven-plugin`. You get:
- `KitchenServiceGrpc.java` — the client stub and server base class.
- `TicketRequest.java`, `TicketResponse.java`, etc. — immutable message objects.

**Step 3 — Implement the server side:**

```java
// KitchenServiceImpl.java — extends the generated base class
public class KitchenServiceImpl extends KitchenServiceGrpc.KitchenServiceImplBase {

    @Override
    public void createTicket(TicketRequest request,
                             StreamObserver<TicketResponse> responseObserver) {
        repository.createTicket(request.getOrderId());

        responseObserver.onNext(TicketResponse.newBuilder().setSuccess(true).build());
        responseObserver.onCompleted();
    }
}
```

**Step 4 — Start the gRPC server:**

```java
// KitchenServiceServer.java
Server server = ServerBuilder
    .forPort(50052)
    .addService(new KitchenServiceImpl(repository))
    .build();
server.start();
```

**Step 5 — Call it from the Orchestrator (client side):**

```java
// SagaOrchestrator.java
ManagedChannel kitchenChannel = NettyChannelBuilder
    .forAddress(new InetSocketAddress("kitchen-service", 50052))
    .negotiationType(NegotiationType.PLAINTEXT)
    .build();

KitchenServiceGrpc.KitchenServiceBlockingStub kitchenStub =
    KitchenServiceGrpc.newBlockingStub(kitchenChannel);

// This looks like a local method call, but it goes over the network:
TicketResponse response = kitchenStub.createTicket(
    TicketRequest.newBuilder().setOrderId(orderId).build()
);
```

`BlockingStub` means the call blocks the current thread until the response arrives. There is also `AsyncStub` (non-blocking) and `FutureStub` (returns a Future).

### The `ManagedChannel`

A `ManagedChannel` represents a persistent connection to a gRPC server. It is expensive to create, so it is created once per service in the constructor of `SagaOrchestrator` and reused for every Saga step.

`NettyChannelBuilder` uses the Netty networking library. `NegotiationType.PLAINTEXT` disables TLS — acceptable for traffic inside a private Docker network, not for production over the public internet.

---

## 3.4 Protocol Buffers (protobuf)

Protobuf is a **binary serialization format**. Given a `.proto` schema, it encodes data as a compact byte stream.

Example comparison for `{ "orderId": "ORDER-001", "amount": 45.0 }`:
- JSON: 41 bytes (text, human-readable)
- Protobuf: ~14 bytes (binary, field-number tags instead of field name strings)

Field numbers in protobuf are **immutable identifiers** — they are what gets embedded in the wire format, not the field name. This means you can rename a field without breaking old clients, as long as you keep the same field number.

---

## 3.5 The Saga Pattern

### The Problem: Distributed Transactions

In a monolith you have a single database, so you use **ACID transactions**: all steps succeed together, or all are rolled back. In microservices, each service has its own database. There is no single transaction that spans all of them.

The Saga pattern solves this by breaking one big transaction into a sequence of **local transactions**, each in its own service, with **compensating transactions** that undo completed steps if a later step fails.

### Orchestration vs Choreography

There are two styles of Saga:

| Style            | How it works                                         | This project uses |
|------------------|------------------------------------------------------|-------------------|
| **Orchestration**| A central coordinator (the Orchestrator) calls each service in order and handles failures. | YES |
| **Choreography** | Services listen to events and react; no central coordinator. | No |

Orchestration is easier to reason about — you can read the entire Saga flow in one file (`SagaOrchestrator.java`). The downside is that the Orchestrator becomes a single point of failure.

### The Saga in This Project — Step by Step

The entire Saga logic lives in `SagaOrchestrator.java:placeOrder()`.

```
placeOrder(orderId, amount)
│
├─ Step 1: Order → APPROVAL_PENDING   [gRPC → order-service]
│
├─ Step 2: Kitchen → CreateTicket     [gRPC → kitchen-service]
│   └─ FAILED? → Order → REJECTED  (stop here, no compensation needed yet)
│
├─ Step 3: Accounting → AuthorizeCard [gRPC → accounting-service]
│   ├─ amount < 100  → AUTHORIZED
│   │   └─ Order → APPROVED  ✅  (happy path complete)
│   │
│   └─ amount >= 100 → NOT AUTHORIZED
│       ├─ COMPENSATION: Kitchen → RejectTicket  (undo Step 2)
│       └─ Order → REJECTED  ❌  (saga complete, compensated)
```

#### Step 1: APPROVAL_PENDING

```java
orderStub.updateStatus(UpdateStatusRequest.newBuilder()
    .setOrderId(orderId)
    .setStatus("APPROVAL_PENDING")
    .build());
```

Before doing anything irreversible, we record the intent. This allows a monitor (or the user) to see that a Saga is in progress.

#### Step 2: Create Kitchen Ticket (Execution transaction)

```java
var ticketResponse = kitchenStub.createTicket(
    TicketRequest.newBuilder().setOrderId(orderId).build());
boolean ticketCreated = ticketResponse.getSuccess();
```

The kitchen-service writes a row to `kitchen_db.tickets` with status `CREATED`. If this fails for any reason (exception or `success=false`), the order is immediately set to `REJECTED` — no compensation needed because nothing else has been committed yet.

#### Step 3: Authorize Payment (Execution + Compensation trigger)

```java
var authResponse = accountingStub.authorizeCard(
    AuthorizeRequest.newBuilder()
        .setOrderId(orderId)
        .setAmount(amount)
        .build());
boolean authorized = authResponse.getAuthorized();
```

The accounting-service applies the business rule: `amount < 100 → authorized = true`. It writes the result to `accounting_db.payments`.

**Happy path (authorized = true):** Set order to `APPROVED`. Done.

**Compensation path (authorized = false):**

```java
// Undo Step 2 (best-effort)
kitchenStub.rejectTicket(RejectRequest.newBuilder().setOrderId(orderId).build());

// Always finalize the order
orderStub.updateStatus(... "REJECTED" ...);
```

The `rejectTicket` call is **best-effort** (wrapped in try-catch, non-fatal): if the kitchen-service is down, we still move forward and set the order to `REJECTED`. The comment in the code says "Best-effort: reject the kitchen ticket; order must be finalized regardless." This reflects a real production decision — you choose which compensations are mandatory and which are best-effort.

### Saga States

| Status             | Meaning                                      |
|--------------------|----------------------------------------------|
| `APPROVAL_PENDING` | Saga started, not yet committed              |
| `APPROVED`         | Saga completed successfully                  |
| `REJECTED`         | Saga failed (either at Step 2 or after compensation) |

### Why Compensation Is Not a Rollback

A database rollback is atomic and invisible — as if nothing happened. A Saga compensation is a **new forward-direction transaction** that sets state to "undone". The intermediate state (e.g., a kitchen ticket with status `REJECTED`) is visible in the database. This is called **eventual consistency** — the system will be consistent eventually, but may be temporarily inconsistent.

---

## 3.6 Service-by-Service Breakdown

### order-service

**Responsibility:** Stores the canonical order state.

**Two servers in one process:**
- gRPC server on port `50051` → accepts `UpdateStatus` calls from the Orchestrator.
- HTTP server on port `8081` → accepts `GET /orders` and `GET /orders/{id}` from the API Gateway.

**Database table:**
```sql
CREATE TABLE orders (
    order_id VARCHAR(255) PRIMARY KEY,
    status   VARCHAR(50)  NOT NULL
);
```

`upsertOrder()` uses `INSERT ... ON CONFLICT DO UPDATE` (PostgreSQL upsert) so the same order can be updated multiple times (APPROVAL_PENDING → APPROVED).

**Key files:**
- `OrderServiceServer.java` — starts both servers, registers shutdown hook.
- `OrderServiceImpl.java` — gRPC handler: calls `repository.upsertOrder()`.
- `OrderHttpServer.java` — uses `com.sun.net.httpserver.HttpServer` (built-in Java, zero dependencies).
- `OrderRepository.java` — JDBC calls to PostgreSQL.

### kitchen-service

**Responsibility:** Manages kitchen tickets.

**gRPC-only** (port `50052`). No HTTP server — the UI never talks to it directly.

**Two rpc methods:**
- `CreateTicket` → writes `status='CREATED'` to `kitchen_db.tickets`. Called in the **execution** phase.
- `RejectTicket` → updates `status='REJECTED'`. Called in the **compensation** phase.

This is the textbook Saga pattern: every execution transaction has a paired compensating transaction.

**Database table:**
```sql
CREATE TABLE tickets (
    order_id VARCHAR(255) PRIMARY KEY,
    status   VARCHAR(50)  NOT NULL
);
```

### accounting-service

**Responsibility:** Authorizes payments.

**gRPC-only** (port `50053`).

**Business rule (from the lab spec):** `amount < 100 → authorized`.

```java
boolean authorized = amount < 100;
```

The actual amount threshold makes this deterministic and testable without a real payment processor.

**Database table:**
```sql
CREATE TABLE payments (
    order_id   VARCHAR(255)     PRIMARY KEY,
    amount     DOUBLE PRECISION NOT NULL,
    authorized BOOLEAN          NOT NULL
);
```

### order-orchestrator

**Responsibility:** Runs the Saga. The "brain" of the system.

**HTTP server** (port `8084`) exposes:
- `POST /saga/order` — triggers the Saga.
- `GET /health` — health check.

Uses Java's built-in `com.sun.net.httpserver.HttpServer` (zero Spring, zero Javalin — minimal footprint).

Uses **Gson** to parse incoming JSON and serialize the `SagaResult` response.

Has **three gRPC channels** (one per downstream service), created at startup and reused:
```java
ManagedChannel orderChannel      → order-service:50051
ManagedChannel kitchenChannel    → kitchen-service:50052
ManagedChannel accountingChannel → accounting-service:50053
```

`SagaResult` is a plain Java object with three fields: `orderId`, `status`, `message`. Gson serializes it to JSON automatically.

### api-gateway

**Responsibility:** Single entry point for the UI. Hides the internal topology from the browser.

**Spring Boot** on port `8080` (exposed as `8088` on the host).

**Three routes:**

| Route | Forwards to |
|-------|-------------|
| `POST /api/orders` | `orchestrator:8084/saga/order` |
| `GET /api/orders` | `order-service:8081/orders` |
| `GET /api/orders/{id}` | `order-service:8081/orders/{id}` |
| `GET /api/health` | returns `{"status":"UP"}` locally |

Uses `RestTemplate` (Spring's synchronous HTTP client) with a 5-second connect timeout and 10-second read timeout.

`@CrossOrigin(origins = "*")` adds CORS headers so the React dev server (on a different port) can call this API during local development.

### frontend

**React 18** single-page application. Uses `axios` for HTTP calls.

In development: `"proxy": "http://localhost:8088"` in `package.json` proxies `/api/*` requests to the gateway, so you don't need CORS during local dev.

In production (Docker): the React app is served statically, and all `/api/*` requests go through the same gateway origin.

---

## 3.7 Data Flow — Full Request Lifecycle

Let's trace `POST /api/orders { "orderId": "ORDER-007", "amount": 45.0 }`:

```
1. User clicks "Place Order" in React
2. React: axios.post('/api', { orderId: 'ORDER-007', amount: 45 })
3. Gateway receives POST /api/orders
4. Gateway: RestTemplate.postForEntity('http://order-orchestrator:8084/saga/order', body)
5. Orchestrator receives POST /saga/order, calls SagaOrchestrator.placeOrder("ORDER-007", 45.0)

6.  [gRPC] orderStub.updateStatus("ORDER-007", "APPROVAL_PENDING")
    → order-service writes: orders(ORDER-007, APPROVAL_PENDING)

7.  [gRPC] kitchenStub.createTicket("ORDER-007")
    → kitchen-service writes: tickets(ORDER-007, CREATED)
    → returns success=true

8.  [gRPC] accountingStub.authorizeCard("ORDER-007", 45.0)
    → accounting-service: 45 < 100 → authorized=true
    → writes: payments(ORDER-007, 45.0, true)
    → returns authorized=true

9.  [gRPC] orderStub.updateStatus("ORDER-007", "APPROVED")
    → order-service writes: orders(ORDER-007, APPROVED)

10. Orchestrator returns SagaResult { orderId:"ORDER-007", status:"APPROVED", message:"Order placed successfully" }
11. Gateway returns the JSON to React
12. React shows "✅ Order ORDER-007 APPROVED!"
```

For `amount = 150` (rejected path), steps 8-9 become:
```
8.  accounting-service: 150 >= 100 → authorized=false
9.  [gRPC] kitchenStub.rejectTicket("ORDER-007")  ← compensation
    → kitchen-service updates: tickets(ORDER-007, REJECTED)
10. [gRPC] orderStub.updateStatus("ORDER-007", "REJECTED")
11. SagaResult { status: "REJECTED", message: "Payment failed: amount >= 100" }
```

---

## 3.8 Distributed Consistency Trade-offs

### Idempotency

`upsertOrder` uses `INSERT ... ON CONFLICT DO UPDATE`. This means calling it twice with the same `orderId` and `status` is safe — the second call just overwrites with the same value. This is **idempotency**: the same operation can be applied multiple times without changing the outcome beyond the first application. Critical in distributed systems where retries are common.

### At-Least-Once Delivery

Because we use synchronous gRPC with no message queue, if the Orchestrator crashes mid-Saga, the operation is lost. A production system would use an event log (Kafka, Outbox pattern) to guarantee at-least-once delivery.

### No Distributed Locking

Gourmet-Go does not prevent two concurrent Sagas from running with the same `orderId`. The `ON CONFLICT DO UPDATE` makes the final state deterministic (last write wins), but there is no distributed lock. A production system would use a database-level advisory lock or optimistic concurrency (a version column).

---

# Part II — DevOps & SRE Deep Dive

## 4.1 Docker — Concepts and Images

**Docker** is a platform for packaging and running applications in **containers** — isolated processes that share the host OS kernel but have their own filesystem, network interface, and process tree.

### Key Concepts

| Concept | Definition |
|---------|-----------|
| **Image** | A read-only template (like a class). Built from a `Dockerfile`. |
| **Container** | A running instance of an image (like an object). |
| **Layer** | Each instruction in a Dockerfile adds a filesystem layer. Layers are cached and shared between images. |
| **Registry** | A server that stores images. Docker Hub is the public registry. |
| **Tag** | A label on an image version (e.g., `fdebbich/order-service:latest`). |

### Why Docker for Microservices?

Without Docker, each developer needs to install Java 17, PostgreSQL, and the right versions of every library on their machine. With Docker:
- "Works on my machine" becomes irrelevant — the container is the machine.
- Each service is isolated — two services can use different Java versions.
- Deployment is just `docker run` instead of a complex installation script.

---

## 4.2 Dockerfiles — Line-by-Line

Every service (except the frontend) uses this same pattern:

```dockerfile
# 1. Base image — Alpine Linux + Eclipse Temurin JRE 17
#    "jre" not "jdk" — we only need to RUN Java, not compile it.
#    "alpine" is a 5 MB minimal Linux — keeps image size small.
FROM eclipse-temurin:17-jre-alpine

# 2. Set the working directory inside the container filesystem.
#    All subsequent commands run relative to /app.
WORKDIR /app

# 3. Copy the pre-built fat JAR from the Maven build output
#    (target/ on the host) into /app/app.jar inside the image.
COPY target/order-service-1.0.0.jar app.jar

# 4. Document which ports this container listens on.
#    EXPOSE is metadata — it does not actually open ports.
#    Port mapping is done in docker-compose.yml.
EXPOSE 50051 8081

# 5. The command to run when the container starts.
#    ENTRYPOINT cannot be overridden by docker run arguments (unlike CMD).
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Why a Fat JAR?

`mvn clean package` creates a **fat JAR** (also called an uber-JAR or shaded JAR): a single `.jar` file that contains all dependencies (gRPC libraries, PostgreSQL JDBC driver, etc.) bundled inside. The container only needs Java installed — it does not need Maven or any library on the classpath.

### Multi-stage Builds (not used here, but important to know)

A more advanced Dockerfile would use two stages:
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/order-service-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```
The final image does not contain Maven or source code — only the JRE and the JAR. This project pre-builds with Maven on the host and then copies the JAR, which is simpler but produces the same result.

---

## 4.3 Docker Compose — Orchestrating Locally

`docker-compose.yml` defines and wires together **all containers** needed to run the full system with a single command:

```bash
docker-compose up --build
```

### Structure of `docker-compose.yml`

```yaml
version: '3.8'   # Compose file format version

services:         # Each key here = one container
  orders-db:      # Container name used as DNS hostname inside the network
    image: postgres:15-alpine  # Pull from Docker Hub instead of building
    environment:
      POSTGRES_DB: orders_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    networks:
      - gourmet-net
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 10

  order-service:
    build: ./order-service   # Build from the Dockerfile in ./order-service/
    environment:
      DB_HOST: orders-db     # Refers to the container named "orders-db"
      DB_USER: postgres
      DB_PASS: postgres
      DB_PORT: 5432
    ports:
      - "8081:8081"          # host_port:container_port
      - "50051:50051"
    depends_on:
      orders-db:
        condition: service_healthy   # Wait for healthcheck to pass
    networks:
      - gourmet-net
```

### `build` vs `image`

- `build: ./order-service` — build a new image from the Dockerfile in that directory.
- `image: postgres:15-alpine` — pull a pre-built image from Docker Hub.

The databases use `image:` because PostgreSQL is already packaged correctly. Our custom services use `build:` because we need to compile and package them.

---

## 4.4 Networking in Docker

### The `gourmet-net` Bridge Network

All services are attached to a custom bridge network called `gourmet-net`:

```yaml
networks:
  gourmet-net:
    driver: bridge
```

**What `bridge` means:** Docker creates a virtual Ethernet bridge on the host. Containers on the same bridge network can reach each other using their **service name as a hostname** (Docker's built-in DNS).

So inside the `order-orchestrator` container, `kitchen-service` resolves to the IP address of the `kitchen-service` container. This is why `SagaOrchestrator.java` does:

```java
String kitchenHost = getEnv("KITCHEN_SERVICE_HOST", "localhost");
// In Docker Compose, KITCHEN_SERVICE_HOST = "kitchen-service"
// Docker DNS resolves "kitchen-service" → 172.18.0.x
```

**Service discovery** in this project is simply Docker DNS. In production (Kubernetes), you would use a service registry like CoreDNS or Consul.

### Port Mapping

```yaml
ports:
  - "8081:8081"   # "host_port:container_port"
```

This means:
- `8081` on your laptop (host) → `8081` inside the container.
- Without this, the container port is only reachable from within the Docker network.

The api-gateway maps `"8088:8080"` — the gateway listens on `8080` inside the container, but you access it at `localhost:8088` on your laptop. This is useful when port 8080 is already taken on your host.

### Which services need external ports?

| Service | External port | Why |
|---------|--------------|-----|
| order-service | 8081, 50051 | Developers may want to query orders directly or test gRPC |
| kitchen-service | 8082, 50052 | Direct access for debugging |
| accounting-service | 8083, 50053 | Direct access for debugging |
| order-orchestrator | 8084 | For testing the Saga directly without the UI |
| api-gateway | 8088 | The only port the browser actually needs |

In production, only the api-gateway (or a real load balancer) would be exposed. All internal ports would be closed.

---

## 4.5 Health Checks and Startup Order

### `depends_on` with `condition: service_healthy`

Without health checks, `order-service` might start before `orders-db` is ready to accept connections, causing the service to crash on startup.

```yaml
order-service:
  depends_on:
    orders-db:
      condition: service_healthy
```

This tells Compose: "Do not start `order-service` until `orders-db` passes its health check."

### The Health Check

```yaml
orders-db:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres"]
    interval: 5s      # Run the check every 5 seconds
    timeout: 5s       # Consider it failed if it takes > 5 seconds
    retries: 10       # After 10 consecutive failures, mark as unhealthy
```

`pg_isready` is a PostgreSQL utility that returns exit code 0 when the server is accepting connections. Docker checks the exit code — 0 = healthy, non-zero = unhealthy.

### Startup Order in This System

```
1. orders-db, kitchen-db, accounting-db  (start simultaneously)
2. order-service    (waits for orders-db to be healthy)
   kitchen-service  (waits for kitchen-db)
   accounting-service (waits for accounting-db)
3. order-orchestrator (depends_on order-service, kitchen-service, accounting-service)
4. api-gateway      (depends_on order-orchestrator)
```

Note: `order-orchestrator` uses `depends_on` without `condition: service_healthy` — this means it starts after those containers *start*, but not necessarily after they are *ready* to accept gRPC connections. In practice, the JVM startup time of the microservices is long enough that the orchestrator's gRPC channels connect successfully.

---

## 4.6 Environment Variables and Configuration

Environment variables are the standard way to configure containerized applications. They externalize configuration so the same image can run in dev, staging, and production with different settings.

### In `docker-compose.yml`

```yaml
order-service:
  environment:
    DB_HOST: orders-db    # Docker DNS name of the database container
    DB_USER: postgres
    DB_PASS: postgres
    DB_PORT: 5432
```

### In Java code

```java
// OrderRepository.java
String host = System.getenv("DB_HOST") != null
    ? System.getenv("DB_HOST") : "localhost";
```

The `System.getenv()` call reads environment variables at runtime. The fallback (`"localhost"`) is used when running the service outside Docker (e.g., on a developer's machine).

### In Spring Boot (api-gateway)

```yaml
# application.yml
services:
  orchestrator-url: http://${ORCHESTRATOR_HOST:localhost}:8084
  order-service-url: http://${ORDER_SERVICE_HOST:localhost}:8081
```

`${ORCHESTRATOR_HOST:localhost}` means: read the `ORCHESTRATOR_HOST` environment variable; if it is not set, use `localhost`. Spring Boot resolves these at startup.

### Security Note

Hardcoding passwords (`postgres`) is fine for local development and labs, but a production system would use **secrets management** (Docker Secrets, HashiCorp Vault, Kubernetes Secrets) so credentials are never stored in plaintext configuration files or environment variables.

---

## 4.7 Ports — Inside vs. Outside the Container

Understanding ports is critical for debugging:

```
Your browser                   Docker host               Container
    |                              |                          |
    | :8088 ──────────────────────>| port-map 8088→8080  ──>| :8080  (api-gateway)
    |                              |                          |
    |                              | (internal only)         |
    |                              |  api-gateway → orchestrator:8084
    |                              |  orchestrator → order-service:50051
    |                              |  orchestrator → kitchen-service:50052
    |                              |  orchestrator → accounting-service:50053
```

- **Container-to-container** communication uses container port numbers on the `gourmet-net` bridge network. No port mapping needed.
- **Host-to-container** communication requires a `ports:` mapping in docker-compose.yml.
- **Browser** can only reach ports that are exposed to the host.

---

## 4.8 CI/CD Pipeline with GitHub Actions

The file `.github/workflows/blank.yml` defines an automated pipeline that runs on every push to the `main` branch.

### Pipeline Stages

```yaml
on:
  push:
    branches: [ main ]   # Trigger: any push to main
```

#### Stage 1: Checkout

```yaml
- name: Checkout
  uses: actions/checkout@v4
```

Downloads the source code from the repository into the runner's workspace.

#### Stage 2: Set Up Java

```yaml
- name: Set up JDK 17
  uses: actions/setup-java@v4
  with:
    java-version: '17'
    distribution: 'temurin'
    cache: maven           # Cache ~/.m2 between runs to speed up builds
```

Installs Eclipse Temurin JDK 17. `cache: maven` saves the Maven local repository between pipeline runs — avoids downloading the same JARs every time.

#### Stage 3: Build All Services

```yaml
- name: Build with Maven
  run: mvn clean package -DskipTests
```

Runs from the root `pom.xml` (parent POM), which builds all five modules:
- `order-service`, `kitchen-service`, `accounting-service`, `order-orchestrator`, `api-gateway`

`-DskipTests` skips unit tests. In a production pipeline you would run tests here and fail the build if they fail.

#### Stage 4: Login to Docker Hub

```yaml
- name: Login to Docker Hub
  uses: docker/login-action@v3
  with:
    username: ${{ secrets.DOCKER_USERNAME }}
    password: ${{ secrets.DOCKER_PASSWORD }}
```

`${{ secrets.DOCKER_USERNAME }}` reads a GitHub Actions secret — a value stored encrypted in the repository settings. Never hardcode credentials in workflow files.

#### Stage 5: Build and Push Docker Images

```yaml
- name: Push order-service
  uses: docker/build-push-action@v5
  with:
    context: ./order-service   # Directory containing the Dockerfile
    push: true
    tags: fdebbich/order-service:latest
```

This action:
1. Builds a Docker image from `./order-service/Dockerfile`.
2. Tags it as `fdebbich/order-service:latest`.
3. Pushes it to Docker Hub.

This is repeated for all five services. After the pipeline runs, anyone can pull these images with `docker pull fdebbich/order-service:latest`.

### What the CI/CD Pipeline Guarantees

| Guarantee | How |
|-----------|-----|
| Code compiles | `mvn clean package` fails if compilation fails |
| Images are reproducible | Same Dockerfile + same JAR = same image |
| Images are versioned | Tagged with `:latest` (could also use `:git-sha`) |
| Images are pushed automatically | No manual `docker push` needed |

### What Is Missing (for production)

- Running tests (unit, integration, end-to-end).
- Scanning the image for vulnerabilities (e.g., `docker scout`, Trivy).
- Deploying to a staging environment automatically.
- Tagging with the Git commit SHA instead of `latest` (so you can roll back to a specific commit).
- Notifications (Slack, email) on failure.

---

## 4.9 SRE Principles Applied to This System

**SRE (Site Reliability Engineering)** is the discipline of making software systems reliable, scalable, and maintainable. Here is how its principles apply to Gourmet-Go.

### Observability

**Logs:** Every service prints structured messages to stdout:
```
[Saga] Step 1: Setting APPROVAL_PENDING
[KitchenService] createTicket called for ORDER-007
[AccountingService] authorizeCard called: ORDER-007 amount=45.0
```

In production, a log aggregator (ELK Stack, Loki, Datadog) would collect these from all containers and make them searchable. Currently, you can view them with `docker-compose logs -f order-orchestrator`.

**Health endpoints:**
- `GET http://localhost:8088/api/health` → `{"status":"UP","service":"api-gateway"}`
- `GET http://localhost:8084/health` → `{"status":"UP"}`

A load balancer or Kubernetes liveness probe would poll these to detect dead services.

**Metrics (not implemented, but should be):** Prometheus would scrape metrics like:
- `saga_duration_seconds` — how long each Saga takes.
- `saga_rejected_total` — how many Sagas end in rejection.
- `grpc_requests_total` — RPC call counts by method and status.

### Reliability Patterns

**Timeout:** `RestTemplate` in the gateway has a 5-second connect timeout and 10-second read timeout. This prevents one slow service from blocking the gateway indefinitely.

**Shutdown hook:** Every service registers a shutdown hook:
```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    System.out.println("[OrderService] Shutting down...");
    grpcServer.shutdown();
}));
```
When Docker sends `SIGTERM` to stop a container, this hook runs, giving the gRPC server time to finish in-flight requests before the process exits. This is **graceful shutdown**.

**Health checks** (Docker Compose) ensure services only receive traffic when they are truly ready.

### Scalability

In Docker Compose, you can scale a stateless service with:
```bash
docker-compose up --scale kitchen-service=3
```
But Gourmet-Go has a problem: the Orchestrator creates gRPC channels at startup with a hardcoded hostname. Scaling `kitchen-service` to 3 replicas would require a load balancer in front of them.

In Kubernetes, this is solved with a `Service` resource — a stable DNS name that load-balances across all healthy pods.

### Single Points of Failure

The current design has several SPOFs:
- The Orchestrator — if it crashes mid-Saga, the order is left in `APPROVAL_PENDING` forever.
- The API Gateway — if it crashes, the UI goes down.
- Each database — no replication is configured.

Production fixes: run multiple replicas behind a load balancer, use managed databases with automatic failover (e.g., AWS RDS Multi-AZ).

### The Twelve-Factor App

Gourmet-Go follows several [12-Factor App](https://12factor.net/) principles:
- **Config in environment variables** (not hardcoded).
- **Stateless processes** (state is in the database, not in memory).
- **Port binding** (each service binds its own port).
- **Logs as streams** (stdout, not files).
- **One codebase** per service (separate Maven modules).

---

## 5. How to Run the System

### Prerequisites

- Docker and Docker Compose installed.
- Maven 3.9+ and JDK 17+ (for local build without Docker).

### Build and Start Everything

```bash
# 1. Build all JARs (required before building Docker images)
mvn clean package -DskipTests

# 2. Start all containers
docker-compose up --build

# 3. Open the UI
open http://localhost:8088
```

### Test the Happy Path (amount < 100)

```bash
curl -X POST http://localhost:8084/saga/order \
     -H "Content-Type: application/json" \
     -d '{"orderId":"TEST-001","amount":45}'
# Expected: {"orderId":"TEST-001","status":"APPROVED","message":"Order placed successfully"}
```

### Test the Rejection Path (amount >= 100)

```bash
curl -X POST http://localhost:8084/saga/order \
     -H "Content-Type: application/json" \
     -d '{"orderId":"TEST-002","amount":150}'
# Expected: {"orderId":"TEST-002","status":"REJECTED","message":"Payment failed: amount >= 100"}
```

### View Logs

```bash
docker-compose logs -f order-orchestrator   # Watch Saga steps in real time
docker-compose logs -f order-service        # See status updates
docker-compose logs -f accounting-service   # See payment decisions
```

### Inspect the Database

```bash
docker exec -it orders-db psql -U postgres -d orders_db -c "SELECT * FROM orders;"
docker exec -it kitchen-db psql -U postgres -d kitchen_db -c "SELECT * FROM tickets;"
docker exec -it accounting-db psql -U postgres -d accounting_db -c "SELECT * FROM payments;"
```

### Stop Everything

```bash
docker-compose down          # Stop and remove containers (data is lost)
docker-compose down -v       # Also remove volumes (PostgreSQL data)
```

---

## 6. Quick-Reference Cheat Sheet

### Saga State Machine

```
[START] → APPROVAL_PENDING → APPROVED   (happy path)
                           → REJECTED   (kitchen failed OR payment failed + compensated)
```

### gRPC Proto Files

| Service           | Proto file           | Methods                         |
|-------------------|----------------------|---------------------------------|
| order-service     | order.proto          | `UpdateStatus`                  |
| kitchen-service   | kitchen.proto        | `CreateTicket`, `RejectTicket`  |
| accounting-service| accounting.proto     | `AuthorizeCard`                 |

### Port Map

| Container name       | Internal gRPC | Internal HTTP | Host HTTP |
|----------------------|---------------|---------------|-----------|
| order-service        | 50051         | 8081          | 8081      |
| kitchen-service      | 50052         | —             | 8082      |
| accounting-service   | 50053         | —             | 8083      |
| order-orchestrator   | —             | 8084          | 8084      |
| api-gateway          | —             | 8080          | **8088**  |

### Technology Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 18, Axios |
| API Gateway | Spring Boot 3, RestTemplate |
| Orchestrator | Plain Java, Gson, `com.sun.net.httpserver` |
| gRPC Services | Java gRPC 1.60, Netty, protobuf 3.25 |
| Databases | PostgreSQL 15 (one per service) |
| Build | Maven 3, Java 17 (Temurin) |
| Containers | Docker, Docker Compose 3.8 |
| CI/CD | GitHub Actions |
| Image registry | Docker Hub (`fdebbich/`) |

### Key Exam Questions & Answers

**Q: Why does each service have its own database?**  
A: To enforce loose coupling. Services communicate only through APIs, not by reading each other's tables. This allows independent scaling and schema changes.

**Q: What is the difference between a Saga and a database transaction?**  
A: A database transaction is atomic (all-or-nothing) and handled by the database engine. A Saga is a sequence of local transactions coordinated by an orchestrator; failures are handled by explicit compensating transactions, not automatic rollback.

**Q: What happens if the kitchen-service is down when the Saga runs?**  
A: The `createTicket` gRPC call throws an exception. The Saga catches it, sets `ticketCreated = false`, and immediately sets the order to `REJECTED`. No compensation is needed because accounting has not been charged yet.

**Q: What happens if the accounting-service crashes after authorizing but before the order is set to APPROVED?**  
A: The Saga dies mid-execution. The order stays in `APPROVAL_PENDING`, and the kitchen ticket stays `CREATED`. This is a known limitation — a production system would use idempotency keys and a persistent Saga log to resume.

**Q: What is `NegotiationType.PLAINTEXT` in the gRPC channel?**  
A: It disables TLS. The connection is unencrypted. This is acceptable inside a private Docker bridge network but would be a security issue over the public internet.

**Q: Why does `docker-compose.yml` use `condition: service_healthy` instead of just `depends_on`?**  
A: Plain `depends_on` only waits for the container to *start*. PostgreSQL needs a few seconds after starting before it accepts connections. `service_healthy` waits until `pg_isready` returns success, preventing "connection refused" errors at startup.

**Q: What is a fat JAR?**  
A: A single `.jar` file that contains the application code AND all its dependencies bundled inside. The container only needs a JRE — no separate library installation required.

**Q: How does the Orchestrator find `kitchen-service` in Docker?**  
A: Docker Compose creates a bridge network (`gourmet-net`) with built-in DNS. The service name `kitchen-service` automatically resolves to the container's IP. The Orchestrator reads `KITCHEN_SERVICE_HOST` from the environment (set to `kitchen-service` in `docker-compose.yml`) and uses it as the hostname for the gRPC channel.
