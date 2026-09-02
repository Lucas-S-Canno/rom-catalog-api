#!/usr/bin/env bash
# Smoke test for the built container image.
#   scripts/smoke.sh [image]        (default: rom-catalog-api:ci)
#
# Brings up Postgres + MinIO + the image via docker-compose.smoke.yml, then checks
# the key contracts: liveness, readiness, auth, migrations, and the fail-fast guard.
set -euo pipefail

# Stop Git Bash / MSYS from rewriting in-container paths like `-cp /app/app.jar`.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

IMAGE="${1:-${API_IMAGE:-rom-catalog-api:ci}}"
PROJECT="rcsmoke"
COMPOSE="docker compose -p ${PROJECT} -f docker-compose.smoke.yml"
BASE="http://localhost:8081"
export API_IMAGE="$IMAGE"

cleanup() { $COMPOSE down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

pass() { printf '  ok   %s\n' "$1"; }
fail() { printf '  FAIL %s\n' "$1"; exit 1; }

echo "== image: $IMAGE =="

# 1. Fail-fast guard: production mode + dev defaults must refuse to start.
echo "-- fail-fast config guard"
if out=$(docker run --rm -e APP_ENV=production "$IMAGE" 2>&1); then
  fail "container started with APP_ENV=production and dev defaults"
else
  echo "$out" | grep -q "Refusing to start" && pass "refuses to start with a clear message" \
    || fail "wrong error: $out"
fi

# 2. Bring the stack up and wait for health.
echo "-- starting stack"
$COMPOSE up -d --wait

# 3. Contract checks.
echo "-- endpoint contracts"
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

[ "$(code "$BASE/health")" = 200 ]        && pass "GET /health -> 200"          || fail "GET /health"
[ "$(code "$BASE/health/ready")" = 200 ]  && pass "GET /health/ready -> 200"    || fail "GET /health/ready"
[ "$(code "$BASE/roms")" = 401 ]          && pass "GET /roms (no token) -> 401" || fail "GET /roms unauth"

echo "-- minting a token from the image"
TOKEN=$($COMPOSE run --rm --no-deps -T --entrypoint java api \
  -cp /app/app.jar com.lucascanno.romcatalog.auth.TokenIssuerCliKt --scope user 2>/dev/null \
  | tr -d '\r' | grep -E '^eyJ' | tail -n1) || true
[ -n "${TOKEN:-}" ] || fail "could not mint a token from the image"
pass "issueToken CLI runs inside the image"

[ "$(code -H "Authorization: Bearer $TOKEN" "$BASE/roms")" = 200 ] \
  && pass "GET /roms (with token) -> 200" || fail "GET /roms auth"

# 4. Migrations actually ran.
$COMPOSE logs api 2>&1 | grep -Eq "Successfully applied|Schema .* is up to date|migrated" \
  && pass "flyway migrations applied on boot" || fail "no flyway output in logs"

echo "== smoke passed =="
