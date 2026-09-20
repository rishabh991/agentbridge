#!/usr/bin/env bash
# Drives the gateway over the MCP streamable-HTTP transport the way a client does:
# initialize -> initialized -> tools/list -> tools/call, twice with identical
# arguments to show the idempotency key holding.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
MCP="$GATEWAY/mcp"
ACCEPT='Accept: application/json, text/event-stream'
JSON='Content-Type: application/json'

say() { printf '\n=== %s ===\n' "$1"; }

# Streamable HTTP answers with SSE framing ("data:{...}") or a plain JSON body.
# Strip the prefix when present and keep the first JSON object either way.
unwrap() { sed 's/^data:[[:space:]]*//' | grep -m1 '^{'; }

say "initialize"
HEADERS=$(mktemp)
INIT=$(curl -sS -D "$HEADERS" -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -d '{
  "jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},
            "clientInfo":{"name":"agentbridge-smoke","version":"1.0.0"}}}' | unwrap)
echo "$INIT" | head -c 400; echo
SESSION=$(grep -i '^mcp-session-id:' "$HEADERS" | tr -d '\r' | awk '{print $2}')
rm -f "$HEADERS"
[[ -n "$SESSION" ]] || { echo "FAIL: no Mcp-Session-Id returned" >&2; exit 1; }
echo "session: $SESSION"
SID="Mcp-Session-Id: $SESSION"

curl -sS -o /dev/null -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -H "$SID" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'

say "tools/list"
TOOLS=$(curl -sS -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -H "$SID" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | unwrap)
echo "$TOOLS" | python3 -c '
import json, sys
tools = json.load(sys.stdin)["result"]["tools"]
print(str(len(tools)) + " tools advertised over MCP:")
for t in tools:
    required = t.get("inputSchema", {}).get("required", [])
    print("  " + t["name"].ljust(28) + " required=" + str(required))
'

say "tools/call orders_listCatalogItems"
curl -sS -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -H "$SID" -d '{
  "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"orders_listCatalogItems","arguments":{}}}' | unwrap \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["content"][0]["text"][:200])'

say "tools/call orders_createOrder (twice, identical arguments)"
CALL='{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"orders_createOrder","arguments":{"customerId":"mcp-smoke","sku":"SKU-DATA-50","quantity":1}}}'
FIRST=$(curl -sS -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -H "$SID" -d "$CALL" | unwrap \
  | python3 -c 'import json,sys; print(json.loads(json.load(sys.stdin)["result"]["content"][0]["text"])["id"])')
SECOND=$(curl -sS -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -H "$SID" -d "$CALL" | unwrap \
  | python3 -c 'import json,sys; print(json.loads(json.load(sys.stdin)["result"]["content"][0]["text"])["id"])')
echo "first  order id: $FIRST"
echo "second order id: $SECOND"
if [[ "$FIRST" != "$SECOND" ]]; then
  echo "FAIL: the retried tool call created a second order" >&2
  exit 1
fi
echo "ok: the identical retry replayed the original order"

say "audit trail"
# The audit projection is asynchronous (gateway -> Kafka -> projector -> Postgres),
# so give it a moment before asserting. An empty trail is a failure, not a blank line:
# the whole claim of this milestone is that every call is recorded.
AUDIT='[]'
for _ in $(seq 1 20); do
  AUDIT=$(curl -fsS "$GATEWAY/api/v1/audit?limit=10")
  COUNT=$(echo "$AUDIT" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
  [[ "$COUNT" -ge 3 ]] && break
  sleep 1
done

echo "$AUDIT" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
for e in rows:
    print("  " + e["tool"].ljust(28) + " " + e["outcome"].ljust(16)
          + " http=" + str(e["httpStatus"]) + " " + str(e["latencyMillis"]) + "ms key=" + str(e["idempotencyKey"]))
if len(rows) < 3:
    sys.exit("FAIL: expected at least 3 audited calls, found " + str(len(rows)))
tools = {e["tool"] for e in rows}
missing = {"orders_listCatalogItems", "orders_createOrder"} - tools
if missing:
    sys.exit("FAIL: these calls were not audited: " + ", ".join(sorted(missing)))
keyed = [e for e in rows if e["tool"] == "orders_createOrder"]
if not all(e["idempotencyKey"] for e in keyed):
    sys.exit("FAIL: a mutating call was audited without an idempotency key")
if len({e["idempotencyKey"] for e in keyed}) != 1:
    sys.exit("FAIL: the retried call used a different idempotency key")
print("ok: every call above is on the audit trail, and both createOrder calls share one key")
'

printf '\nM1 MCP smoke passed.\n'
