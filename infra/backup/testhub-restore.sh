#!/usr/bin/env bash
# Restores only persistent TestHub stores after an explicit operator confirmation.
set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  infra/backup/testhub-restore.sh restore --env-file .env.production --backup /secure/backups/testhub-YYYYMMDDTHHMMSSZ --confirm-restore [--compose-file compose.production.yaml] [--dry-run]

This operation destroys the current MySQL database and replaces the MinIO bucket contents.
It deliberately does not replay Redis data: sessions, queue claims, and transient runner state are
flushed so the control plane can rebuild them from durable database records.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

action="${1:-}"
[[ $# -gt 0 ]] && shift
env_file=""
compose_file="compose.production.yaml"
backup=""
confirmed=false
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="${2:-}"; shift 2 ;;
    --compose-file) compose_file="${2:-}"; shift 2 ;;
    --backup) backup="${2:-}"; shift 2 ;;
    --confirm-restore) confirmed=true; shift ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

compose() { docker compose --env-file "$env_file" -f "$compose_file" "$@"; }

verify_backup() {
  "$(dirname "$0")/testhub-backup.sh" verify --backup "$backup"
}

restore() {
  [[ -n "$env_file" && -r "$env_file" ]] || fail "a readable --env-file is required"
  [[ -n "$backup" && -d "$backup" ]] || fail "a valid --backup directory is required"
  [[ -f "$compose_file" ]] || fail "compose file does not exist: $compose_file"
  command -v docker >/dev/null 2>&1 || fail "docker is required"
  command -v gzip >/dev/null 2>&1 || fail "gzip is required"
  verify_backup

  if [[ "$dry_run" == true ]]; then
    note "Dry run: backup is valid. Would require API to be stopped, replace MySQL and MinIO data, then flush Redis volatile state."
    exit 0
  fi
  [[ "$confirmed" == true ]] || fail "restore is destructive; rerun with --confirm-restore after a reviewed maintenance window"
  if [[ -n "$(compose ps -q api)" ]]; then
    fail "the API service is still running; stop it before recovery to prevent concurrent writes"
  fi

  compose config --quiet
  note "Replacing the MySQL database from the verified backup..."
  compose exec -T mysql sh -ec 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE IF EXISTS testhub; CREATE DATABASE testhub CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
  gzip -dc "$backup/mysql.sql.gz" | compose exec -T mysql sh -ec 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" testhub'

  note "Replacing MinIO bucket contents from the verified backup..."
  compose run --rm --no-deps -T -v "$backup/minio:/restore:ro" --entrypoint /bin/sh minio-client -ec '
    mc alias set testhub "$TESTHUB_MINIO_ENDPOINT" "$TESTHUB_MINIO_ACCESS_KEY" "$TESTHUB_MINIO_SECRET_KEY" >/dev/null
    mc mb --ignore-existing "testhub/$TESTHUB_MINIO_BUCKET" >/dev/null
    mc mirror --overwrite --remove /restore "testhub/$TESTHUB_MINIO_BUCKET"
    mc rm --force "testhub/$TESTHUB_MINIO_BUCKET/.empty-bucket" >/dev/null 2>&1 || true
  '

  note "Flushing volatile Redis state..."
  compose exec -T redis sh -ec 'exec redis-cli -a "$TESTHUB_REDIS_PASSWORD" FLUSHALL ASYNC' >/dev/null
  note "Persistent data has been restored. Keep API stopped until Flyway validation and application smoke tests pass; then start API explicitly."
}

case "$action" in
  restore) restore ;;
  -h|--help|"") usage ;;
  *) fail "unknown action: $action" ;;
esac
