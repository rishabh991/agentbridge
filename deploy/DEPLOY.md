# Deploying AgentBridge

**Status: not deployed yet.** Two paths are prepared:

- **Render free tier — the chosen one.** `render.yaml` in the repo root. $0, no card.
- **Fly.io — ~$10/month.** `deploy/fly/` plus `scripts/deploy_fly.sh`. Kept because it is
  the better experience if the demo ever earns the cost: no cold starts.

Neither has been run end to end. The local stack that both are built from is verified on
every commit by CI.

## Render (the chosen path)

### What Rishabh does

1. Sign up at https://render.com with **Continue with GitHub** and authorise it for the
   `agentbridge` repository. (Account creation and OAuth grants are his; Claude cannot do
   either.)
2. Have the Neon connection string on the clipboard: Neon console → project `agentbridge`
   → Connect → copy the URI.

### What the blueprint does

New → Blueprint → pick the repo. Render reads `render.yaml` and creates two free web
services in Singapore, generating `UPSTREAM_TOKEN`, `AGENTBRIDGE_ADMIN_KEY` and
`AGENTBRIDGE_GUEST_KEY` itself. The six database variables are marked `sync: false`, so
Render prompts for them — they are the only values typed by hand:

| Variable | From the Neon URI `postgresql://USER:PASSWORD@HOST/DB?sslmode=require` |
|---|---|
| `ORDERS_DB_URL`, `GATEWAY_DB_URL` | `jdbc:postgresql://HOST/DB?sslmode=require` |
| `ORDERS_DB_USER`, `GATEWAY_DB_USER` | `USER` |
| `ORDERS_DB_PASSWORD`, `GATEWAY_DB_PASSWORD` | `PASSWORD` |

Both services share one Neon database; the gateway's tables live in a `gateway` schema
that Flyway creates on first boot, the orders tables in `public`.

### What the free plan forces, and how the code answers it

| Constraint | Answer |
|---|---|
| A free service can *send* private-network requests but not *receive* them, so the gateway must call the upstream's **public** URL | The upstream requires `X-Upstream-Token` whenever `UPSTREAM_TOKEN` is set. Render generates it and passes the same value to the gateway. An order and payment API open to the internet would make a nonsense of a project about governed access |
| No broker | `AUDIT_SINK=direct` writes audit rows straight to Postgres. The dead-letter topic and the Kafka-failure fallback are Kafka-sink features, so on Render a Postgres outage fails the audit write outright — the trade a free host buys |
| Services sleep after 15 min idle; ~30–60s to wake, plus JVM start | The tool registry retries the OpenAPI import every 60s while it holds no tools, so a gateway that woke before its upstream does not sit there serving an empty tool list |
| 512 MB per service | `-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k`. If the gateway OOMs on boot, that is the first thing to revisit |
| 750 instance-hours/month across free services | Two services that sleep when idle stay well inside it; two services pinned awake would not |

### After it is up

```bash
curl https://agentbridge-gateway.onrender.com/api/v1/info      # no key needed
```

Take the generated guest key from the Render dashboard (gateway service → Environment) and
put it in the README's connect snippet, so a visitor can point an MCP client at the demo
without asking for anything.

## Fly.io (the paid alternative)

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

## Runbook

Two prerequisites, both in a browser:

1. `fly auth login` — and the Fly account needs a **confirmed payment method**, or every
   API call fails with the account locked.
2. Copy the Neon connection string: Neon console → project `agentbridge` → Connect → copy
   the URI.

Then one command:

```bash
./scripts/deploy_fly.sh
```

It reads the connection string from the clipboard (so the password is never pasted into a
chat, a file or a log), creates both apps, generates the admin and guest API keys into the
git-ignored `deploy/.generated-keys.env`, stages every secret, deploys the upstream and then
the gateway, and finally calls `/api/v1/info` and lists the tools the guest key can see.

Flyway creates the `gateway` schema on first boot; the orders tables migrate the same way,
both against the same Neon database.

Re-running it is safe: existing apps are reused and existing keys are kept.

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
