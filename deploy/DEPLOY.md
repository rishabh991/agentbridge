# Deploying AgentBridge

**Status: not deployed.** The manifests in `deploy/fly/` are written from the Fly.io
docs and have never been run — there is no Fly account yet. Treat them as a starting
point, not a tested path.

## What is blocking it

Not the code any more. M2 added API keys, per-tool scopes and per-key rate limits, so the
gateway is safe to expose. What is missing is an account: Fly.io (needs a browser login and
a card on file) and Neon (needs a browser signup). Both are Rishabh's to create.

**Before going public, set both keys explicitly.** With `AGENTBRIDGE_ADMIN_KEY` unset the
gateway generates an admin key per boot and prints it to the log once — acceptable on a
laptop, not in a deployment:

```bash
fly secrets set -a agentbridge-gateway \
  AGENTBRIDGE_ADMIN_KEY="ab_$(openssl rand -base64 32 | tr -d '/+=' | head -c 43)" \
  AGENTBRIDGE_GUEST_KEY="ab_$(openssl rand -base64 32 | tr -d '/+=' | head -c 43)"
```

The guest key holds `*:read` only, so a public demo can call read tools and nothing else.

Until deployed, the demo runs locally: `docker compose up --build -d`, then `make smoke`.

## The shape when it does deploy

| Piece | Where | Note |
|---|---|---|
| Gateway | Fly.io, 512 MB, `sin` | Public. `AUDIT_SINK=direct` — no broker at this size |
| orders-service | Fly.io, 512 MB, internal only | Reached over `.internal` DNS, never exposed |
| Postgres | Neon free tier | Two schemas: `public` for orders, `gateway` for audit |
| Kafka | not deployed | The direct audit sink replaces it; the README says so rather than implying a broker is running. Note the dead-letter topic and the Kafka-failure fallback only apply to the Kafka sink — with `AUDIT_SINK=direct` a Postgres outage fails the audit write outright, which is the trade a single small host buys |

## Runbook (once an account exists)

```bash
brew install flyctl
fly auth login                        # browser; Rishabh does this
fly apps create agentbridge-orders
fly apps create agentbridge-gateway

# Neon: create a project, then one connection string per service
fly secrets set -a agentbridge-orders \
  ORDERS_DB_URL='jdbc:postgresql://<host>/<db>?sslmode=require' \
  ORDERS_DB_USER='<user>' ORDERS_DB_PASSWORD='<password>'
fly secrets set -a agentbridge-gateway \
  GATEWAY_DB_URL='jdbc:postgresql://<host>/<db>?sslmode=require' \
  GATEWAY_DB_USER='<user>' GATEWAY_DB_PASSWORD='<password>'

fly deploy -c deploy/fly/orders.fly.toml
fly deploy -c deploy/fly/gateway.fly.toml

curl https://agentbridge-gateway.fly.dev/api/v1/info
```

Flyway creates the `gateway` schema on first boot; the orders schema migrates the same way.

## Things that will bite

- **Memory.** Two JVMs at 512 MB each is tight. `-XX:MaxRAMPercentage=70` and SerialGC are
  already set; if the gateway OOMs on boot, go to 1 GB before tuning anything else.
- **Cold starts.** `auto_stop_machines = "suspend"` means the first request after idle pays
  a wake-up. Fine for a demo, worth saying out loud in the walkthrough.
- **Neon connection limits.** The free tier is small; keep HikariCP's pool at or below 5
  per service (`spring.datasource.hikari.maximum-pool-size`).
- **Rate limits are per instance.** Scaling the gateway past one machine multiplies every
  key's limit by the machine count. Keep `min_machines_running` at 1 and do not scale out
  until there is a shared limiter.
- **Tool import at boot.** The gateway imports the upstream's OpenAPI doc at startup. If
  orders-service is suspended, the gateway starts with an empty tool list — call
  `POST /api/v1/tools/refresh` once both are awake, or set `min_machines_running = 1` on
  orders.
