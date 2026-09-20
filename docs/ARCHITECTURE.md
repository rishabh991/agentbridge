# Architecture notes

## Shape

Three planes, kept separate on purpose:

- **Control plane** — what tools exist and who may call them. Upstreams are declared in
  `gateway/src/main/resources/application.yml` under `agentbridge.upstreams`; from M1 the
  registry imports each upstream's OpenAPI document and derives MCP tools from it.
- **Data plane** — the actual tool call: policy check, idempotency, circuit breaker, upstream
  HTTP call, response mapping.
- **Observability plane** — every call produces an audit event and a cost/latency sample.
  Audit events go to Kafka (Redpanda locally) and are projected into Postgres for querying;
  the UI subscribes over SSE.

## Why an MCP gateway rather than an MCP server per service

A server per service duplicates auth, rate limiting, audit and cost accounting in every
team's codebase, and gives the security team N places to review. One gateway in front of N
declared upstreams gives a single policy surface, a single audit stream, and one place to
answer "what can this agent do".

The cost is a hop and a single point of failure, which is why M2 puts circuit breakers and
per-upstream isolation in, and M5 puts tracing across the hop.

## Decisions so far

| Decision | Why | Revisit when |
|---|---|---|
| Upstreams declared in config, never discovered | An auto-discovering agent gateway eventually exposes a service nobody meant to expose | A service registry with explicit opt-in labels exists |
| Idempotency implemented in the upstream, enforced by the gateway | The invariant belongs with the data; the gateway's job is to guarantee a key is always sent | Never — this split is the point |
| Redpanda rather than Kafka locally | One container, no ZooKeeper/KRaft ceremony, Kafka wire protocol | Demo host memory gets tight; then fall back to a Postgres outbox and say so in the README |
| H2 for unit tests, Testcontainers later (M5) | Fast feedback now, fidelity before anyone depends on it | M5 — and sooner if this bites again: H2 with `ddl-auto: create-drop` cannot see entity/migration drift, so an M0 `char(3)` vs `varchar(3)` mismatch got through the suite. `ddl-auto: validate` against real Postgres caught it at boot, which is the second half of the reason it is set that way |
| Flyway from M0 | A demo whose schema is `ddl-auto: update` is not a demo of production practice | Never |

## Audit event (shape planned for M1)

```json
{
  "eventId": "uuid",
  "at": "2026-09-20T11:02:03.114Z",
  "apiKeyId": "key_guest",
  "tool": "orders.createOrder",
  "upstream": "orders",
  "arguments": { "redacted": true },
  "outcome": "ok | denied | upstream_error | rate_limited",
  "httpStatus": 201,
  "latencyMillis": 42,
  "idempotencyKey": "…",
  "provider": "anthropic",
  "tokensIn": 0,
  "tokensOut": 0,
  "costMicros": 0
}
```

Arguments are redacted by default; M2 adds a per-tool allowlist of fields that may be logged
in full, because an audit log that contains card numbers is a liability, not a control.
