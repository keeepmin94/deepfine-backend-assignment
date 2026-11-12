#!/usr/bin/env bash
set -euo pipefail

source_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$source_root"
verification_dir=$(mktemp -d "${TMPDIR:-/tmp}/deepfine-compose.XXXXXX")
project_name="deepfine-verify-$(date +%s)-$$"
compose=(docker compose --project-name "$project_name" --env-file .env.example)
export POSTGRES_USER=inventory
export POSTGRES_PASSWORD=compose-verification-only
export DB_PORT=0
export SERVER_PORT=0
export STOCK_LOCK_TIMEOUT_MS=3000

cleanup() {
    result=$?
    trap - EXIT
    if [ "$result" -ne 0 ]; then
        "${compose[@]}" logs --tail=100 api db || true
    fi
    if ! "${compose[@]}" down --volumes --remove-orphans; then
        result=1
    fi
    rm -rf "$verification_dir"
    exit "$result"
}
trap cleanup EXIT

fail() {
    printf '%s\n' "$*" >&2
    exit 1
}

sql() {
    "${compose[@]}" exec -T db psql -X -A -t -v ON_ERROR_STOP=1 -U inventory -d inventory -c "$1"
}

endpoint() {
    published=$("${compose[@]}" port api 8080)
    base_url="http://$published"
}

wait_for_api() {
    for attempt in $(seq 1 60); do
        readiness_code=$(curl --silent --output /dev/null --write-out '%{http_code}' \
            --connect-timeout 1 --max-time 2 "$base_url/api/v1/products/COMPOSE-READY/stock" || true)
        if [ "$readiness_code" = 200 ] || [ "$readiness_code" = 404 ]; then
            return
        fi
        sleep 1
    done
    fail "API did not become ready within the bounded polling window"
}

request() {
    method=$1
    path=$2
    expected_status=$3
    payload=${4:-}
    arguments=(--silent --show-error --connect-timeout 3 --max-time 10
        --dump-header "$verification_dir/headers" --output "$verification_dir/body"
        --write-out '%{http_code}' --request "$method")
    if [ -n "$payload" ]; then
        arguments+=(--header 'Content-Type: application/json' --data "$payload")
    fi
    status=$(curl "${arguments[@]}" "$base_url$path")
    [ "$status" = "$expected_status" ] || fail "$method $path expected $expected_status, got $status"
    request_id=$(awk 'tolower($1) == "x-request-id:" {gsub("\r", "", $2); print $2}' "$verification_dir/headers")
    [[ "$request_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] \
        || fail "Missing or invalid server request ID"
}

quantity() {
    grep -Eq "\"$1\"[[:space:]]*:[[:space:]]*$2([[:space:]]*[,}])" "$verification_dir/body" \
        || fail "Unexpected $1 in HTTP response"
}

assert_database() {
    [ "$(sql 'SELECT count(*) FROM product')" = 1 ] || fail "Unexpected product count"
    [ "$(sql 'SELECT count(*) FROM inventory')" = 1 ] || fail "Unexpected inventory count"
    [ "$(sql 'SELECT quantity FROM inventory')" = 7 ] || fail "Unexpected final stock"
    [ "$(sql 'SELECT count(*) FROM stock_movement')" = 2 ] || fail "Unexpected movement count"
    [ "$(sql "SELECT sum(CASE WHEN movement_type = 'INBOUND' THEN quantity ELSE -quantity END) FROM stock_movement")" = 7 ] \
        || fail "History does not match inventory"
}

"${compose[@]}" config --quiet
"${compose[@]}" up --build --detach --wait --wait-timeout 90
"${compose[@]}" exec -T api java -version
"${compose[@]}" exec -T api sh -c 'test -f /app/app.jar && test ! -e /workspace && ! command -v javac >/dev/null 2>&1'
endpoint
wait_for_api
case "$(sql 'SHOW server_version')" in
    16.14*) ;;
    *) fail "Unexpected PostgreSQL version" ;;
esac
[ "$(sql "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")" = 1 ] \
    || fail "Flyway V1 was not applied"
[ "$(sql 'SELECT count(*) FROM product')" = 0 ] || fail "New verification volume is not empty"

request POST /api/v1/stock/inbounds 201 '{"productCode":"COMPOSE-001","name":"Compose product","quantity":10}'
quantity quantityAfter 10
inbound_request_id=$request_id
request POST /api/v1/stock/outbounds 201 '{"productCode":"COMPOSE-001","quantity":3}'
quantity quantityAfter 7
[ "$inbound_request_id" != "$request_id" ] || fail "Request IDs were reused"
request GET /api/v1/products/COMPOSE-001/stock 200
quantity quantity 7
request POST /api/v1/stock/outbounds 409 '{"productCode":"COMPOSE-001","quantity":8}'
grep -Eq '"code"[[:space:]]*:[[:space:]]*"INSUFFICIENT_STOCK"' "$verification_dir/body" \
    || fail "Unexpected business error"
grep -Eq "\"requestId\"[[:space:]]*:[[:space:]]*\"$request_id\"" "$verification_dir/body" \
    || fail "Error body and header request IDs differ"
assert_database

"${compose[@]}" restart db
"${compose[@]}" up --detach --wait --wait-timeout 90 db
"${compose[@]}" restart api
"${compose[@]}" up --detach --wait --wait-timeout 90
endpoint
wait_for_api
request GET /api/v1/products/COMPOSE-001/stock 200
quantity quantity 7
assert_database

"${compose[@]}" down
"${compose[@]}" up --detach --wait --wait-timeout 90
endpoint
wait_for_api
request GET /api/v1/products/COMPOSE-001/stock 200
quantity quantity 7
assert_database
printf '%s\n' "Compose verification passed: build, Flyway, HTTP, request IDs, rejected outbound and persistence"
