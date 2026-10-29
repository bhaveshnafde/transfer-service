#!/usr/bin/env bash
# Challenge 2 evidence: N identical POST /transfers with the SAME Idempotency-Key fired at the
# same moment, then a sequential repeat, then the same key with a different body.
# Expected: exactly one original response, the rest REQUEST_IN_PROGRESS (409) or a replay (201),
# a byte-identical replay afterwards, 422 for the changed body, and one transfer in the database.
#
# Usage: scripts/challenge2-idempotency.sh            (N=10, Transfer service on localhost:8082)
#        N=20 TRANSFER_URL=http://localhost:8082 scripts/challenge2-idempotency.sh
set -euo pipefail

BASE="${TRANSFER_URL:-http://localhost:8082}"
N="${N:-10}"
KEY="idem-demo-$(date +%s)"
OUT="${OUT_DIR:-evidence/challenge-2-idempotency/run-$(date +%Y%m%d-%H%M%S)}"
BODY='{"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002","amount":100.00,"currency":"INR"}'
mkdir -p "$OUT"

post() { # $1 = name, $2 = body
  curl -s -o "$OUT/$1.body.json" -D "$OUT/$1.headers.txt" \
       -H "Content-Type: application/json" -H "Idempotency-Key: $KEY" -H "X-Request-ID: $KEY-$1" \
       -d "$2" "$BASE/api/v1/transfers"
  local status replayed
  status=$(head -1 "$OUT/$1.headers.txt" | awk '{print $2}')
  replayed=$(grep -i '^idempotent-replayed:' "$OUT/$1.headers.txt" | awk '{print $2}' | tr -d '\r' || true)
  echo "$1 status=$status replayed=${replayed:--} body=$(cat "$OUT/$1.body.json")"
}
export -f post; export OUT KEY BASE

{
  echo "== Challenge 2: idempotency   $(date -Is)"
  echo "Idempotency-Key: $KEY"
  echo
  echo "== $N identical requests at the same moment"
  seq -w 1 "$N" | xargs -P "$N" -I{} bash -c "post parallel-{} '$BODY'" | sort
  echo
  echo "== Repeat after it finished (expect 201, replayed=true, identical body)"
  post repeat "$BODY"
  first=$(grep -l '"status":"COMPLETED"' "$OUT"/parallel-*.body.json | head -1)
  if cmp -s "$first" "$OUT/repeat.body.json"; then echo "Replay is byte-identical to the original: YES"; else echo "Replay is byte-identical to the original: NO"; fi
  echo
  echo "== Same key, different amount (expect 422 IDEMPOTENCY_KEY_REUSED)"
  post different-body "${BODY/100.00/999.00}"
  echo
  echo "== Summary"
  echo "status codes: $(cat "$OUT"/parallel-*.headers.txt | grep -h '^HTTP' | awk '{print $2}' | sort | uniq -c | tr '\n' ' ')"
  echo "originals (replayed=false): $(grep -il '^idempotent-replayed: false' "$OUT"/*.headers.txt | wc -l | tr -d ' ')"
  echo "distinct transferIds returned: $(grep -ho '"transferId":"[^"]*"' "$OUT"/*.body.json | sort -u | wc -l | tr -d ' ')"
  echo
  echo "Check the database has ONE transfer for this key:"
  echo "  psql ... -c \"SELECT id, status FROM transfers WHERE idempotency_key = '$KEY';\""
} | tee "$OUT/summary.txt"
