# Architecture notes

## Shape

Three planes, kept separate on purpose:

- **Control plane** — what tools exist and who may call them. Upstreams are declared in
  `gateway/src/main/resources/application.yml` under `agentbridge.upstreams`; the registry
  imports each upstream's OpenAPI document at startup and derives MCP tools from it
  (`OpenApiToolFactory`). The "who may call them" half arrives in M2.
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


## Decisions added in M1

| Decision | Why | Revisit when |
|---|---|---|
| Tool arguments are one flat object | Models handle a flat argument object far better than a nested `{path, query, body}` shape; routing is the gateway's job | A body field legitimately collides with a path parameter often enough to hurt — the clash is logged today |
| Idempotency keys are derived from tool + sorted arguments, not random | A model retrying an identical call must replay, not duplicate. Random keys would make every retry a new order | M2, which lets a client supply its own key and adds a TTL |
| Upstream errors are returned as JSON tool results, not thrown | A model told "409 already_paid" can recover; a transport error teaches it nothing | Never |
| Registry import failures cost tools, not the boot | A gateway that refuses to start because one upstream is slow is the weakest link in the chain it exists to strengthen | Never |
| Audit arguments redacted to field names by default | An audit log full of customer identifiers is a liability, not a control | M2 adds the per-tool allowlist |
| Kafka publish failures are logged and swallowed | M1 will not fail a customer-facing tool call because the broker is down | M2, where a dead-letter topic makes swallowing unnecessary |
| Consumer ignores Kafka type headers and pins the payload type | A consumer that instantiates classes named by the message is a deserialization gadget | Never |
| Spring AI 1.1 on Spring Boot 3.5, not Spring AI 2.0 on Boot 4 | The product fronts *existing* estates; it should not require the host to upgrade Boot first | M5 |

The one that bit during M1: `JsonDeserializer` on the consumer and a `JsonMessageConverter`
bean are mutually exclusive — the deserializer hands the listener a typed object, the
converter demands raw bytes and throws `Only String, Bytes, or byte[] supported`. The
symptom was an empty audit trail while every tool call succeeded, which is exactly the
failure mode an audit system must never have. The smoke script now fails on an empty trail.

## Audit event (as implemented in M1)

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

Arguments are redacted by default — the row carries `{"redacted": true, "fields": [...]}`
rather than values. M2 adds a per-tool allowlist of fields that may be logged in full,
because an audit log that contains card numbers is a liability, not a control.

`apiKeyId` is `anonymous` until M2 issues keys. `provider`, `tokensIn`, `tokensOut` and
`costMicros` are carried from M1 but stay zero until M3 wires the provider side: the shape
is fixed now on purpose, because changing an audit schema after the fact is how audit
trails lose their value.
