# AgentBridge

[![ci](https://github.com/rishabh991/agentbridge/actions/workflows/ci.yml/badge.svg)](https://github.com/rishabh991/agentbridge/actions/workflows/ci.yml)

**A governed MCP gateway for existing Spring services.** Point it at an OpenAPI spec and get auth-scoped, rate-limited, audited, cost-tracked tools that Claude, Bedrock or a local model can call — without rewriting the service underneath.

**Live demo:** https://agentbridge-gateway.onrender.com/api/v1/info · MCP endpoint `https://agentbridge-gateway.onrender.com/mcp`

> **Status: M2 — governed.** Point the gateway at an OpenAPI spec and it serves the operations as MCP tools behind API keys, per-tool scopes, per-key rate limits and upstream circuit breakers, auditing every call — allowed, denied or rate-limited — through Kafka into Postgres. The chat UI, cost tracking and the Python agent side land in M3–M4; the roadmap below says exactly what is and is not wired, and `GET /api/v1/info` reports the same thing at runtime.

---

## The problem

Most enterprises that want agents already have the business logic: a decade of Spring services with real auth, real invariants and real money moving through them. The gap is not intelligence, it is **governance**. Handing an agent a service account and an HTTP client gives you no answer to:

- which tools may this caller use, and with what scopes?
- what happens when the agent retries a payment?
- what did the agent actually call last Tuesday, and what did it cost?
- how do you know a prompt change did not make tool selection worse?

AgentBridge sits between the two and answers those four questions.

## Architecture

```mermaid
flowchart LR
    subgraph clients["Agent clients"]
        claude["Claude Desktop / API"]
        lg["LangGraph agent"]
        sdk["Claude Agent SDK"]
    end

    subgraph gw["AgentBridge gateway · Spring Boot 3.5 · Java 21"]
        mcp["MCP server<br/>(Spring AI)"]
        reg["Tool registry<br/>OpenAPI → tools"]
        pol["Policy layer<br/>API keys · RBAC scopes · rate limits"]
        safe["Call safety<br/>idempotency · circuit breaker"]
        meter["Cost + latency meter"]
        aud["Audit publisher"]
    end

    subgraph up["Upstream services"]
        orders["orders-service<br/>Orders &amp; Payments"]
    end

    subgraph data["Data plane"]
        pg[("PostgreSQL")]
        kafka[["Redpanda / Kafka<br/>audit stream"]]
    end

    ui["Demo UI<br/>chat · live audit · evals"]
    evals["Evals harness<br/>pytest"]

    claude & lg & sdk -->|MCP| mcp
    mcp --> reg --> pol --> safe --> orders
    orders --> pg
    safe --> meter --> aud --> kafka
    kafka --> pg
    aud -->|SSE| ui
    evals -->|results| meter
    reg -.->|imports /v3/api-docs| orders

    classDef planned stroke-dasharray: 5 5;
    class meter,ui,evals planned;
```

Dashed = not implemented yet (M3+). Solid today: the MCP server, the OpenAPI tool registry, the policy layer (keys, scopes, rate limits), idempotency and circuit-broken upstream calls, and the audit stream through Kafka into Postgres.

## Quickstart

Requires Docker (or Colima) and nothing else — the JDK and Maven live inside the build image.

```bash
git clone https://github.com/rishabh991/agentbridge.git
cd agentbridge
cp .env.example .env           # optional; only needed from M3 for LLM calls
docker compose up --build -d   # postgres + redpanda + orders-service + gateway
./scripts/smoke.sh             # upstream: catalogue → order → idempotent replay → payment
./scripts/mcp_smoke.sh         # gateway: MCP initialize → tools/list → tools/call, twice
```

First build pulls the Maven and Temurin images and takes a few minutes; after that it is cached.

| URL | What |
|---|---|
| http://localhost:8080/mcp | **The MCP endpoint** (streamable HTTP) — point an MCP client here |
| http://localhost:8080/api/v1/tools | The generated tool surface, with the routing detail behind each tool |
| http://localhost:8080/api/v1/audit | The audit trail, newest first |
| http://localhost:8080/api/v1/info | Gateway version, tool count and honest capability flags |
| http://localhost:8080/api/v1/upstreams | Declared upstreams and their reachability |
| http://localhost:8081/swagger-ui.html | The upstream API the tools are generated from |
| http://localhost:8081/v3/api-docs | The OpenAPI spec itself |

Tear down with `docker compose down`, or `docker compose down -v` to drop the database volume too.

## What M1 actually does

**One OpenAPI document in, six governed MCP tools out.** No tool is hand-written; change the upstream's spec and the tool surface follows on the next refresh (`POST /api/v1/tools/refresh`).

```
orders_listCatalogItems   GET  /api/catalog/items          read-only
orders_listOrders         GET  /api/orders                 read-only
orders_getOrder           GET  /api/orders/{id}            read-only
orders_listPayments       GET  /api/orders/{id}/payments   read-only
orders_createOrder        POST /api/orders                 mutating → idempotency key
orders_capturePayment     POST /api/orders/{id}/payments   mutating → idempotency key
```

Three decisions worth the words:

- **Arguments are one flat object.** Path, query and JSON body fields all become top-level tool arguments. Models handle a flat object far better than `{path: {...}, body: {...}}`, and routing is the gateway's business, not the model's.
- **Mutating tools carry a derived idempotency key.** The key is a hash of the tool name and the canonical (field-sorted) arguments, so a model that retries the identical call after a timeout replays the original order rather than placing a second one. `scripts/mcp_smoke.sh` calls `orders_createOrder` twice and asserts both calls return the same order id and share one key.
- **Upstream errors come back as data.** A 409 is returned to the model as `{"error":"upstream_error","upstream":{"code":"already_paid",...}}`. A model told "the order is already paid" can recover; a transport exception teaches it nothing.

### Connecting a client

The MCP endpoint is streamable HTTP and requires an API key. Point Claude Code at the live
demo with the read-only guest key:

```bash
claude mcp add --transport http agentbridge https://agentbridge-gateway.onrender.com/mcp \
  --header "Authorization: Bearer A4HpX+A7L8Kg/fyH0HssWxFywjuf1ijCJ3Z6oK18F+k="
```

That key holds `*:read`, so an agent can list the catalogue and read orders but is refused
`orders_createOrder` — try it and the model is told exactly which scope it lacks. It is
capped at 60 tool calls a minute, and every call it makes is on the audit trail.

Running locally instead, the same command against `http://localhost:8080/mcp` with
`ab_local-guest-key` (or `ab_local-admin-key` to allow writes). Any MCP client works —
`scripts/mcp_smoke.sh` is a `curl` client if you want to see the raw protocol.

> **It is on a free tier.** Both services sleep after 15 minutes idle, so the first request
> after a quiet spell takes about a minute to wake them — JVM start on top of the platform's
> cold start. It is a demo, not a service level.

### Every call is audited

Each tool call produces exactly one audit event — success, upstream error or gateway error alike — carrying the tool, upstream, outcome, HTTP status, latency and idempotency key. Events go to Kafka (`agentbridge.audit`), and a projector consumes them back into Postgres so `GET /api/v1/audit` can answer "what did the agent do".

**Arguments are redacted by default**: the audit row records which fields were sent, never their values. An audit log that quietly accumulates customer identifiers and payment amounts is a liability, not a control — M2 adds a per-tool allowlist for fields that may be logged in full.

If the host cannot spare memory for a broker, set `AUDIT_SINK=direct` and the gateway writes to Postgres itself. Same table, same rows, one less moving part — the README promised that fallback at M0 and it is real.

## What M2 adds: the governance layer

Every tool call now passes three gates before it reaches an upstream, and produces an audit row whichever gate stops it.

### 1. API keys

Keys are `ab_`-prefixed random secrets. **Only the SHA-256 hash is stored** — a leaked database is not a set of working credentials, and a key can be replaced but never recovered. A key is shown exactly once, when it is created:

```bash
curl -X POST localhost:8080/api/v1/keys -H 'Authorization: Bearer ab_local-admin-key' \
  -H 'Content-Type: application/json' \
  -d '{"label":"reporting-agent","scopes":["orders:read"],"requestsPerMinute":60}'
```

Present it as `Authorization: Bearer <key>` or `X-API-Key: <key>`. `/api/v1/info` and health stay open so a demo page and a load balancer need no credential; everything else does.

### 2. Per-tool scopes

The scope a tool requires is **derived, not configured**: `<upstream>:read` for safe operations, `<upstream>:write` for mutating ones, with `*:read` / `*:write` as wildcards and `admin` as the master key. Derivation is the point — a newly imported tool is governed the moment it appears, rather than sitting unprotected until somebody remembers to write a rule for it.

```
orders_listCatalogItems   requires orders:read
orders_createOrder        requires orders:write
```

A read-only key calling `orders_createOrder` is refused **before the upstream is touched**, and the model is told why:

```json
{"error":"denied","message":"This key does not hold the scope 'orders:write' that tool 'orders_createOrder' requires."}
```

### 3. Per-key rate limits

Each key carries its own `requestsPerMinute`. Over it, the call is refused with `rate_limited` and the model is told the number, so it can back off rather than guess.

> **Per instance.** The limiter is in-process, so two gateway replicas allow twice the rate. A shared limiter needs Redis, which the demo does not run. Stated rather than hidden.

### Circuit breakers

Each upstream gets a breaker: 8+ calls, >50% failures or >70% slow calls (over 5s) opens it for 20 seconds, then three trial calls decide whether to close it. While open, tools return `upstream_unavailable` immediately instead of queueing behind a dying service — otherwise every retrying agent becomes a load generator aimed at the upstream that is already struggling.

**4xx responses are not failures.** A 404 or a 409 is the upstream working correctly and saying no; counting those would open the breaker because an agent asked for something that does not exist.

### Audit, tightened

- Every row now carries **which key made the call** — including denials and rate-limited calls, which are exactly the rows you want attributed.
- Failed Kafka publishes **fall back to a direct Postgres write** rather than being logged and dropped. An audit trail with a hole in it is worse than none, because it is trusted.
- An event that cannot be projected goes to `agentbridge.audit.DLT` after three retries instead of blocking the partition — M1's behaviour turned one poison event into a silent gap in the whole trail.
- Argument values remain redacted to field names, with a **per-tool allowlist** for fields that are safe and useful (`orders_capturePayment` logs `id` and `amountCents`; `customerId` stays a name everywhere).

## The upstream is not a toy

`orders-service` is a deliberately realistic target, because the interesting governance problems only show up against one:

- **Idempotency that actually holds.** `POST /api/orders` and `POST /api/orders/{id}/payments` store an `Idempotency-Key` with a fingerprint of the request. A replay returns the original resource; the same key with a different body is rejected with `409 idempotency_key_reuse`. An agent that retries on timeout cannot double-charge a customer.
- **Money invariants.** A capture must equal the order total; a paid or cancelled order cannot be paid again.
- **A real schema.** Flyway migrations, `timestamptz`, check constraints, foreign keys, indexes on the query paths.
- **A documented contract.** springdoc publishes the OpenAPI spec that the gateway imports in M1, with each mutating operation labelled as such.

## Local development

```bash
make build    # mvn verify — compiles both modules and runs the tests
make test     # tests only
make up       # docker compose up --build -d
make logs     # tail everything
make smoke    # end-to-end check against a running stack
make down     # stop (ARGS=-v also drops the volume)
```

Running the JVM directly needs Java 21 and Maven:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -pl orders-service spring-boot:run   # needs Postgres on :5432
mvn -pl gateway spring-boot:run
```

Tests use H2 in PostgreSQL mode and need no containers. Testcontainers-based integration tests arrive in M5.

## Repo layout

```
gateway/         The gateway — MCP server, OpenAPI tool registry, idempotency, audit
orders-service/  Sample upstream — Orders & Payments with idempotent, documented endpoints
scripts/         smoke.sh (upstream) and mcp_smoke.sh (MCP protocol + audit trail)
deploy/          Fly.io manifests and DEPLOY.md — untested; see below
docs/            Architecture notes and decisions
Dockerfile       One multi-stage recipe, MODULE build-arg selects the module
docker-compose.yml  The whole local stack
```

## Hosting

Deployed to Render's free tier from `render.yaml` (Singapore), with Neon for Postgres and
no broker at all — `AUDIT_SINK=direct` writes audit rows straight to Postgres.
[`deploy/DEPLOY.md`](deploy/DEPLOY.md) has the runbook and an honest list of what the free
plan forces. Two of those constraints changed the code rather than being worked around:

- A free Render service cannot *receive* private-network traffic, so the gateway must call
  the upstream over its public URL. The upstream therefore requires a shared
  `X-Upstream-Token` whenever one is configured — an order and payment API open to the
  internet would make a nonsense of a project about governed access. Locally, on compose's
  private network, no token is set and the filter does not register.
- Free services sleep when idle, so the gateway can boot while its upstream is still
  asleep. The tool registry retries the OpenAPI import every 60s while it holds no tools,
  rather than serving an empty tool list until somebody notices.

## Roadmap

| Milestone | Scope | Status |
|---|---|---|
| **M0** | Repo, compose stack, realistic upstream, architecture docs | ✅ done |
| **M1** | OpenAPI → MCP tools over streamable HTTP; derived idempotency keys; every call audited to Kafka + Postgres | ✅ done |
| **M2** | API keys, per-tool RBAC scopes, per-key rate limits, circuit breakers, dead-letter for failed audits, per-tool argument allowlist | ✅ done |
| **M3** | Chat UI, live tool-call and audit stream over SSE, per-key/tool/provider cost tracking | next |
| **M4** | Python agents (LangGraph + Claude Agent SDK) against the gateway, pytest evals harness, evals dashboard | planned |
| **M5** | End-to-end OTel tracing, Testcontainers integration tests, k8s manifests + HPA, published load-test numbers | planned |
| **M6** | Public demo with a read-only guest key, cost cap, and a 2-minute walkthrough | planned |

## Design notes

- **Declared, not discovered.** Upstreams are listed in configuration. An agent gateway that auto-discovers services is an agent gateway that will one day expose one you did not mean to.
- **The capability endpoint never lies.** `/api/v1/info` is generated from what is wired, so the demo cannot drift ahead of the code.
- **Kafka from day one, Postgres as the fallback.** The audit stream is a stream. If the demo host runs out of memory, the README will say so and the outbox table takes over — not silently, in writing.
- **Stack choices are the boring ones on purpose:** Spring Boot 3.5, Spring AI 1.1, Java 21, Flyway, Resilience4j, OpenTelemetry. Spring AI 2.0 requires Spring Boot 4; a gateway whose selling point is fronting *existing* services should not demand that its host upgrade first. The Boot 4 / Spring AI 2 path is an M5 item, not a prerequisite.
- **The tool surface is generated, never hand-maintained.** A hand-written tool list drifts from the API it fronts, and the drift is silent. Generating from the spec means the spec is the contract for agents too.

## Who built this

Rishabh Gupta — senior Java/Spring Boot + Kafka engineer (6 yrs: AT&T Prepaid, Metro by T-Mobile at 50K+ RPM, Marriott Vacation Club payments), now building LLM and agent integrations into enterprise Java stacks with Spring AI, MCP, Bedrock and Python/LangGraph.

Available for remote contracts. [rishabh.qc991@gmail.com](mailto:rishabh.qc991@gmail.com)

## License

MIT — see [LICENSE](LICENSE).
