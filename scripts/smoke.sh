#!/usr/bin/env bash
# Quick check of the main paths. Works with the dev stubs (seed accounts ACC-1001, ACC-2002,
# ACC-EMPTY, ACC-FROZEN) or with real services seeded the same way.
set -uo pipefail

BASE="${TRANSFER_URL:-http://localhost:8082}"

call() { # $1 = label, $2 = body
  echo "-- $1"
  curl -s -w "\n   [HTTP %{http_code}]\n" -H "Content-Type: application/json" \
       -H "Idempotency-Key: smoke-$(date +%s%N)" -d "$2" "$BASE/api/v1/transfers"
}

echo "-- health"; curl -s -w "\n   [HTTP %{http_code}]\n" "$BASE/health"
call "happy path (expect 201 COMPLETED)" \
  '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":100.00,"currency":"INR"}'
call "BR2 insufficient funds (expect 409 INSUFFICIENT_FUNDS)" \
  '{"sourceAccountId":"ACC-EMPTY","destinationAccountId":"ACC-2002","amount":10.00,"currency":"INR"}'
call "BR4 inactive account (expect 422 ACCOUNT_NOT_ACTIVE)" \
  '{"sourceAccountId":"ACC-FROZEN","destinationAccountId":"ACC-2002","amount":10.00,"currency":"INR"}'
call "BR1 above cap (expect 422 BR1_AMOUNT_ABOVE_CAP)" \
  '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":100000.01,"currency":"INR"}'
call "BR4 same account (expect 422 BR4_SAME_ACCOUNT)" \
  '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-1001","amount":10.00,"currency":"INR"}'
call "invalid fields (expect 400 VALIDATION_FAILED)" \
  '{"sourceAccountId":"x","destinationAccountId":"ACC-2002","currency":"RUPEES"}'
echo "-- missing Idempotency-Key (expect 400 MISSING_IDEMPOTENCY_KEY)"
curl -s -w "\n   [HTTP %{http_code}]\n" -H "Content-Type: application/json" \
     -d '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":1,"currency":"INR"}' \
     "$BASE/api/v1/transfers"
