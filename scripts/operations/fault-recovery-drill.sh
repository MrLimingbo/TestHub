#!/usr/bin/env bash
# Plans or safely verifies evidence for an isolated TestHub recovery drill.
set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  scripts/operations/fault-recovery-drill.sh plan
  scripts/operations/fault-recovery-drill.sh verify --backup /secure/backups/testhub-... [--url URL]

Commands:
  plan     Print the required isolated-environment recovery sequence. This is non-destructive.
  verify   Verify a completed backup's checksums and a control-plane readiness endpoint. This is non-destructive.

The script never stops containers, restores data, or supplies --confirm-restore. A destructive restore
must use infra/backup/testhub-restore.sh in an approved, isolated maintenance environment.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 2; }
note() { printf '%s\n' "$*"; }

action="${1:-plan}"
[[ $# -gt 0 ]] && shift
backup=""
url="http://127.0.0.1:8080/actuator/health/readiness"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup) backup="${2:-}"; shift 2 ;;
    --url) url="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

[[ "$url" =~ ^https?://[^/@?[:space:]]+(/[^?[:space:]]*)?$ ]] || fail "--url must be an HTTP(S) URL without credentials or query values"
[[ "$url" == */actuator/health/readiness ]] || fail "--url must end in /actuator/health/readiness"

plan() {
  note "TestHub isolated recovery drill plan"
  note "1. Use a non-production Docker project, separate MySQL/Redis/MinIO volumes, and a freshly verified backup."
  note "2. Record the backup ID, Flyway version, expected sample records, and an approved recovery owner."
  note "3. Stop only the isolated API service, then run infra/backup/testhub-restore.sh restore with its explicit confirmation."
  note "4. Start dependencies and API, wait for /actuator/health/readiness to report UP, and review Flyway status."
  note "5. Verify a scoped administrator login, an API asset read, a functional-test read, and an object-storage attachment read."
  note "6. Record recovery duration, data checks, runner-state behavior, residual gaps, and the evidence artifact in the change record."
  note "7. Destroy the isolated environment after evidence review; never replay Redis sessions or task claims."
}

verify() {
  [[ -n "$backup" && -d "$backup" ]] || fail "verify requires an existing --backup directory"
  command -v curl >/dev/null 2>&1 || fail "curl is required"
  "$(dirname "$0")/../../infra/backup/testhub-backup.sh" verify --backup "$backup"
  response="$(curl --fail --silent --show-error --connect-timeout 2 --max-time 8 -- "$url")" || fail "readiness endpoint did not return a successful response"
  [[ "$response" == *'"status":"UP"'* ]] || fail "readiness endpoint did not report UP"
  note "Recovery acceptance preconditions verified: backup integrity and readiness are both healthy."
  note "This does not restore data. Execute the planned isolated drill before marking production recovery accepted."
}

case "$action" in
  plan) plan ;;
  verify) verify ;;
  -h|--help) usage ;;
  *) fail "unknown command: $action" ;;
esac
