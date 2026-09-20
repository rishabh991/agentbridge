#!/usr/bin/env bash
# Drives the gateway over MCP the way a client does, as two different API keys:
# a read-only guest and an admin. Proves authentication, per-tool scopes, derived
# idempotency keys, per-key rate limits, and that every one of those outcomes lands
# on the audit trail.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
MCP="$GATEWAY/mcp"
ADMIN_KEY="${AGENTBRIDGE_ADMIN_KEY:-ab_local-admin-key}"
GUEST_KEY="${AGENTBRIDGE_GUEST_KEY:-ab_local-guest-key}"
JSON='Content-Type: application/json'
ACCEPT='Accept: application/json, text/event-stream'
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

say() { printf '\n=== %s ===\n' "$1"; }

# Opens a session for a key and echoes its id.
mcp_session() {
  local key="$1" headers="$WORK/h.$RANDOM"
  curl -sS -D "$headers" -o /dev/null -X POST "$MCP" -H "$JSON" -H "$ACCEPT" \
    -H "Authorization: Bearer $key" 2>/dev/null -d '{
      "jsonrpc":"2.0","id":1,"method":"initialize",
      "params":{"protocolVersion":"2025-06-18","capabilities":{},
                "clientInfo":{"name":"agentbridge-smoke","version":"1.0.0"}}}'
  local session
  session=$(grep -i '^mcp-session-id:' "$headers" | tr -d '\r' | awk '{print $2}')
  [[ -n "$session" ]] || { echo "FAIL: no Mcp-Session-Id for key ${key:0:12}..." >&2; exit 1; }
  curl -sS -o /dev/null -X POST "$MCP" -H "$JSON" -H "$ACCEPT" \
    -H "Authorization: Bearer $key" -H "Mcp-Session-Id: $session" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  echo "$session"
}

# Sends one JSON-RPC request and prints the JSON response.
#
# The response body is written to a file rather than piped: the server answers on an
# SSE stream it keeps open, so a reader that stops at the first JSON object makes curl
# exit 18 ("transfer closed"), which would abort the script under `set -o pipefail`.
# curl's own error output is held back and only shown if no JSON actually arrived, so
# a real connection failure is still reported.
mcp_send() {
  local key="$1" session="$2" body="$3" out="$WORK/r.$RANDOM" err="$WORK/e.$RANDOM"
  curl -sS --max-time 30 -o "$out" -X POST "$MCP" -H "$JSON" -H "$ACCEPT" \
    -H "Authorization: Bearer $key" -H "Mcp-Session-Id: $session" -d "$body" 2>"$err" || true
  if ! sed 's/^data:[[:space:]]*//' "$out" | grep -m1 '^{'; then
    echo "FAIL: no JSON-RPC response from $MCP" >&2
    cat "$err" >&2
    return 1
  fi
}

# The text of a tools/call result.
tool_text() { python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["content"][0]["text"])'; }

say "an unauthenticated client cannot even open a session"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$MCP" -H "$JSON" -H "$ACCEPT" -d '{
  "jsonrpc":"2.0","id":1,"method":"initialize",
  "params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"x","version":"1"}}}')
[[ "$CODE" == "401" ]] || { echo "FAIL: expected 401 without a key, got $CODE" >&2; exit 1; }
echo "ok: 401"

GUEST_SESSION=$(mcp_session "$GUEST_KEY")
ADMIN_SESSION=$(mcp_session "$ADMIN_KEY")

say "tools/list"
mcp_send "$ADMIN_KEY" "$ADMIN_SESSION" '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' | python3 -c '
import json, sys
tools = json.load(sys.stdin)["result"]["tools"]
print(str(len(tools)) + " tools advertised over MCP:")
for t in tools:
    print("  " + t["name"].ljust(28) + " required=" + str(t.get("inputSchema", {}).get("required", [])))
'

say "the guest key reads"
mcp_send "$GUEST_KEY" "$GUEST_SESSION" '{
  "jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"orders_listCatalogItems","arguments":{}}}' | tool_text | head -c 140; echo

say "the guest key is refused a write"
DENIED=$(mcp_send "$GUEST_KEY" "$GUEST_SESSION" '{
  "jsonrpc":"2.0","id":4,"method":"tools/call",
  "params":{"name":"orders_createOrder","arguments":{"customerId":"guest-probe","sku":"SKU-DATA-50","quantity":1}}}' | tool_text)
echo "$DENIED"
echo "$DENIED" | grep -q 'orders:write' || { echo "FAIL: a read-only key was allowed to write" >&2; exit 1; }
echo "ok: refused before the upstream was touched"

say "the admin key performs the same write, twice, with identical arguments"
CALL='{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"orders_createOrder","arguments":{"customerId":"mcp-smoke","sku":"SKU-DATA-50","quantity":1}}}'
FIRST=$(mcp_send "$ADMIN_KEY" "$ADMIN_SESSION" "$CALL" | tool_text | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
SECOND=$(mcp_send "$ADMIN_KEY" "$ADMIN_SESSION" "$CALL" | tool_text | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "first  order id: $FIRST"
echo "second order id: $SECOND"
[[ "$FIRST" == "$SECOND" ]] || { echo "FAIL: the retried tool call created a second order" >&2; exit 1; }
echo "ok: the identical retry replayed the original order"

say "a key is held to its own rate limit"
LIMITED_KEY=$(curl -sS -X POST "$GATEWAY/api/v1/keys" -H "$JSON" -H "Authorization: Bearer $ADMIN_KEY" \
  -d '{"label":"smoke-rate-limited-'"$RANDOM"'","scopes":["orders:read"],"requestsPerMinute":2}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["secret"])')
LIMITED_SESSION=$(mcp_session "$LIMITED_KEY")
ALLOWED=0; LIMITED=0
for _ in 1 2 3 4; do
  RESULT=$(mcp_send "$LIMITED_KEY" "$LIMITED_SESSION" '{
    "jsonrpc":"2.0","id":6,"method":"tools/call",
    "params":{"name":"orders_listCatalogItems","arguments":{}}}' | tool_text)
  if echo "$RESULT" | grep -q 'rate_limited'; then LIMITED=$((LIMITED+1)); else ALLOWED=$((ALLOWED+1)); fi
done
echo "allowed=$ALLOWED rate_limited=$LIMITED (key is capped at 2/minute)"
[[ "$ALLOWED" == "2" && "$LIMITED" == "2" ]] || { echo "FAIL: the rate limit did not hold" >&2; exit 1; }

say "audit trail"
# Projection is asynchronous (gateway -> Kafka -> projector -> Postgres). An empty or
# incomplete trail is a failure: the claim of this project is that every call is recorded.
for _ in $(seq 1 20); do
  AUDIT=$(curl -fsS "$GATEWAY/api/v1/audit?limit=20" -H "Authorization: Bearer $ADMIN_KEY")
  COUNT=$(echo "$AUDIT" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
  [[ "$COUNT" -ge 8 ]] && break
  sleep 1
done

echo "$AUDIT" | python3 -c '
import json, sys
rows = json.load(sys.stdin)
for e in rows[:10]:
    print("  " + str(e["apiKeyId"]).ljust(22) + e["tool"].ljust(26) + e["outcome"].ljust(14)
          + "http=" + str(e["httpStatus"]).ljust(6) + "key=" + str(e["idempotencyKey"]))

outcomes = {e["outcome"] for e in rows}
for required in ("ok", "denied", "rate_limited"):
    if required not in outcomes:
        sys.exit("FAIL: no audit row with outcome " + required)

callers = {e["apiKeyId"] for e in rows}
if "anonymous" in {e["apiKeyId"] for e in rows if e["outcome"] in ("denied", "rate_limited")}:
    sys.exit("FAIL: a governed decision was recorded without the key that caused it")

writes = [e for e in rows if e["tool"] == "orders_createOrder" and e["outcome"] == "ok"
          and e["apiKeyId"] == "key_bootstrap_admin"]
if len(writes) < 2 or len({e["idempotencyKey"] for e in writes[:2]}) != 1:
    sys.exit("FAIL: the two identical writes did not share one idempotency key")

for e in rows:
    if "guest-probe" in str(e["arguments"]) or "mcp-smoke" in str(e["arguments"]):
        sys.exit("FAIL: a customer identifier was written to the audit trail in full")

print("ok: allowed, denied and rate-limited calls are all on the trail, attributed to their keys,")
print("    the retried write shares one key, and no customer identifier was recorded")
'

printf '\nM2 MCP smoke passed.\n'
