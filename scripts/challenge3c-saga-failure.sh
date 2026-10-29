#!/usr/bin/env bash
# Challenge 3C evidence: force a failure at step 3 (Ledger) and show steps 2 and 1 being undone.
#
# With the dev stubs:      USE_STUBS=1 scripts/challenge3c-saga-failure.sh
#   (switches the Ledger stub to answer 503, then back)
# With the real services:  stop the Ledger service (or start it with its failure flag), then run
#   scripts/challenge3c-saga-failure.sh
set -euo pipefail

BASE="${TRANSFER_URL:-http://localhost:8082}"
OUT="${OUT_DIR:-evidence/challenge-3c-saga/run-$(date +%Y%m%d-%H%M%S)}"
KEY="saga-demo-$(date +%s)"
mkdir -p "$OUT"

{
  echo "== Challenge 3C: saga with compensation   $(date -Is)"
  if [[ "${USE_STUBS:-0}" == "1" ]]; then
    echo "Account state BEFORE:"; curl -s -X POST localhost:8081/stub/state; echo
    curl -s -X POST "localhost:8083/stub/fail?on=true" >/dev/null
    echo "Ledger stub switched to fail (503)"
  else
    echo "Make sure the Ledger service is stopped or failing before this request."
  fi
  echo
  echo "== POST /transfers (step 3 will fail)"
  curl -s -D "$OUT/response.headers.txt" -o "$OUT/response.body.json" \
       -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -H "X-Request-ID: $KEY" \
       -d '{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":250.00,"currency":"INR"}' \
       "$BASE/api/v1/transfers"
  head -1 "$OUT/response.headers.txt"
  cat "$OUT/response.body.json"; echo
  TID=$(grep -o '"transferId":"[^"]*"' "$OUT/response.body.json" | head -1 | cut -d'"' -f4)
  echo
  echo "== Saga steps for transfer $TID (each step, then the undo in reverse order)"
  curl -s "$BASE/api/v1/transfers/$TID/saga-steps" | tee "$OUT/saga-steps.json"; echo
  echo
  echo "== Final transfer state"
  curl -s "$BASE/api/v1/transfers/$TID" | tee "$OUT/transfer.json"; echo
  if [[ "${USE_STUBS:-0}" == "1" ]]; then
    curl -s -X POST "localhost:8083/stub/fail?on=false" >/dev/null
    echo; echo "Account state AFTER (balance and available unchanged, hold RELEASED):"
    curl -s -X POST localhost:8081/stub/state; echo
  fi
  echo
  echo "Logs for this run: grep for requestId $KEY in each service's output."
} | tee "$OUT/summary.txt"
