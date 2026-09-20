#!/usr/bin/env bash
# End-to-end check of the M0 stack: catalogue -> order -> idempotent replay -> payment.
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
ORDERS="${ORDERS:-http://localhost:8081}"
KEY="smoke-$(date +%s)"

say() { printf '\n=== %s ===\n' "$1"; }

say "gateway info"
curl -fsS "$GATEWAY/api/v1/info"; echo

say "gateway view of upstreams"
curl -fsS "$GATEWAY/api/v1/upstreams"; echo

say "catalogue"
curl -fsS "$ORDERS/api/catalog/items"; echo

say "create order (Idempotency-Key: $KEY)"
ORDER=$(curl -fsS -X POST "$ORDERS/api/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"smoke-customer","sku":"SKU-ROAM-10","quantity":2}')
echo "$ORDER"
ORDER_ID=$(echo "$ORDER" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
AMOUNT=$(echo "$ORDER" | sed -n 's/.*"amountCents":\([0-9]*\).*/\1/p')

say "replay the same key -> must return the same order id"
REPLAY=$(curl -fsS -X POST "$ORDERS/api/orders" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d '{"customerId":"smoke-customer","sku":"SKU-ROAM-10","quantity":2}')
REPLAY_ID=$(echo "$REPLAY" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
if [[ "$ORDER_ID" != "$REPLAY_ID" ]]; then
  echo "FAIL: replay created a second order ($ORDER_ID vs $REPLAY_ID)" >&2
  exit 1
fi
echo "ok: replay returned $REPLAY_ID"

say "capture payment of $AMOUNT cents"
curl -fsS -X POST "$ORDERS/api/orders/$ORDER_ID/payments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: pay-$KEY" \
  -d "{\"amountCents\":$AMOUNT}"; echo

say "order is now PAID"
curl -fsS "$ORDERS/api/orders/$ORDER_ID"; echo

printf '\nM0 smoke passed.\n'
