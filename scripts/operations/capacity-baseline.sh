#!/usr/bin/env bash
# Runs a bounded, control-plane-only baseline against the readiness probe.
set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  scripts/operations/capacity-baseline.sh [options]

Options:
  --url URL                 Readiness URL (must end in /actuator/health/readiness).
                            Default: http://127.0.0.1:8080/actuator/health/readiness
  --requests NUMBER         Total probes, between 10 and 1000. Default: 60
  --concurrency NUMBER      Concurrent probes, between 1 and 32. Default: 6
  --p95-ms NUMBER           Passing p95 latency threshold in milliseconds. Default: 500
  --max-error-percent NUM   Passing maximum error percentage. Default: 1
  --output FILE             JSON evidence artifact. Default: artifacts/operations/capacity-baseline.json

This script is deliberately not a general load generator. It does not send credentials, request bodies,
or traffic to business APIs. It can only probe the control-plane readiness endpoint and records no URL.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 2; }
note() { printf '%s\n' "$*"; }
is_integer() { [[ "$1" =~ ^[0-9]+$ ]]; }
is_decimal() { [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]]; }

url="http://127.0.0.1:8080/actuator/health/readiness"
requests=60
concurrency=6
p95_ms=500
max_error_percent=1
output="artifacts/operations/capacity-baseline.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) url="${2:-}"; shift 2 ;;
    --requests) requests="${2:-}"; shift 2 ;;
    --concurrency) concurrency="${2:-}"; shift 2 ;;
    --p95-ms) p95_ms="${2:-}"; shift 2 ;;
    --max-error-percent) max_error_percent="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

[[ "$url" =~ ^https?://[^/@?[:space:]]+(/[^?[:space:]]*)?$ ]] || fail "--url must be an HTTP(S) URL without credentials or query values"
[[ "$url" == */actuator/health/readiness ]] || fail "--url must end in /actuator/health/readiness"
is_integer "$requests" && (( requests >= 10 && requests <= 1000 )) || fail "--requests must be between 10 and 1000"
is_integer "$concurrency" && (( concurrency >= 1 && concurrency <= 32 )) || fail "--concurrency must be between 1 and 32"
is_integer "$p95_ms" && (( p95_ms >= 10 && p95_ms <= 60000 )) || fail "--p95-ms must be between 10 and 60000"
is_decimal "$max_error_percent" || fail "--max-error-percent must be a non-negative number"
awk -v value="$max_error_percent" 'BEGIN { exit !(value >= 0 && value <= 100) }' || fail "--max-error-percent must be between 0 and 100"
command -v curl >/dev/null 2>&1 || fail "curl is required"

artifact_dir="$(dirname "$output")"
mkdir -p "$artifact_dir"
results_dir="$(mktemp -d "${TMPDIR:-/tmp}/testhub-capacity.XXXXXX")"
trap 'rm -rf "$results_dir"' EXIT

probe() {
  local index="$1" raw status seconds latency
  raw="$(curl --silent --show-error --output /dev/null --write-out '%{http_code} %{time_total}' \
    --connect-timeout 2 --max-time 10 -- "$url" 2>/dev/null || true)"
  status="${raw%% *}"
  seconds="${raw##* }"
  if [[ ! "$status" =~ ^[0-9]{3}$ ]] || ! [[ "$seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    status="000"
    seconds="10"
  fi
  latency="$(awk -v value="$seconds" 'BEGIN { printf "%d", (value * 1000) + 0.5 }')"
  printf '%s %s\n' "$status" "$latency" > "$results_dir/$index"
}

note "Running bounded readiness baseline: $requests probes, concurrency $concurrency."
for ((index = 1; index <= requests; index++)); do
  probe "$index" &
  if (( index % concurrency == 0 )); then wait; fi
done
wait

results="$results_dir/results.txt"
cat "$results_dir"/* | sort -n -k2,2 > "$results"
sample_count="$(wc -l < "$results" | tr -d ' ')"
error_count="$(awk '$1 !~ /^2/ { failures += 1 } END { print failures + 0 }' "$results")"
p50_index=$(( (sample_count + 1) / 2 ))
p95_index=$(( (sample_count * 95 + 99) / 100 ))
p50="$(awk -v row="$p50_index" 'NR == row { print $2; exit }' "$results")"
p95="$(awk -v row="$p95_index" 'NR == row { print $2; exit }' "$results")"
maximum="$(awk 'END { print $2 }' "$results")"
error_percent="$(awk -v failures="$error_count" -v total="$sample_count" 'BEGIN { printf "%.2f", failures * 100 / total }')"

passed=1
(( p95 <= p95_ms )) || passed=0
awk -v actual="$error_percent" -v threshold="$max_error_percent" 'BEGIN { exit !(actual <= threshold) }' || passed=0
if (( passed == 1 )); then result="PASSED"; else result="FAILED"; fi

generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  printf '{\n'
  printf '  "kind": "testhub-control-plane-capacity-baseline",\n'
  printf '  "generatedAt": "%s",\n' "$generated_at"
  printf '  "result": "%s",\n' "$result"
  printf '  "sampleCount": %s,\n' "$sample_count"
  printf '  "concurrency": %s,\n' "$concurrency"
  printf '  "p50LatencyMs": %s,\n' "$p50"
  printf '  "p95LatencyMs": %s,\n' "$p95"
  printf '  "maxLatencyMs": %s,\n' "$maximum"
  printf '  "errorCount": %s,\n' "$error_count"
  printf '  "errorPercent": %s,\n' "$error_percent"
  printf '  "thresholds": { "p95LatencyMs": %s, "maxErrorPercent": %s }\n' "$p95_ms" "$max_error_percent"
  printf '}\n'
} > "$output"

note "Capacity baseline $result. Evidence: $output"
(( passed == 1 )) || exit 1
