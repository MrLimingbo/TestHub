#!/usr/bin/env bash
# Creates an integrity-checked TestHub backup without placing credentials in the archive.
# Persistent recovery sources are MySQL and MinIO. Redis is saved for incident forensics only.
set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  infra/backup/testhub-backup.sh create --env-file .env.production --output /secure/backups [--compose-file compose.production.yaml] [--dry-run]
  infra/backup/testhub-backup.sh verify --backup /secure/backups/testhub-YYYYMMDDTHHMMSSZ

The command talks only to Docker Compose services. It does not start the application stack.
The archive contains a compressed MySQL dump, a MinIO object mirror, an optional Redis forensic
snapshot, a non-secret manifest, and SHA-256 checksums.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

action="${1:-}"
[[ $# -gt 0 ]] && shift
env_file=""
compose_file="compose.production.yaml"
output=""
backup=""
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file="${2:-}"; shift 2 ;;
    --compose-file) compose_file="${2:-}"; shift 2 ;;
    --output) output="${2:-}"; shift 2 ;;
    --backup) backup="${2:-}"; shift 2 ;;
    --dry-run) dry_run=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

compose() { docker compose --env-file "$env_file" -f "$compose_file" "$@"; }

require_backup() {
  [[ -n "$backup" ]] || fail "--backup is required"
  [[ -d "$backup" ]] || fail "backup directory does not exist: $backup"
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}';
  else fail "sha256sum or shasum is required"; fi
}

verify() {
  require_backup
  [[ -f "$backup/manifest.env" ]] || fail "manifest.env is missing"
  [[ -f "$backup/checksums.sha256" ]] || fail "checksums.sha256 is missing"
  [[ -f "$backup/mysql.sql.gz" ]] || fail "mysql.sql.gz is missing"
  [[ -f "$backup/redis.rdb" ]] || fail "redis.rdb is missing"
  [[ -d "$backup/minio" ]] || fail "minio mirror is missing"
  grep -qx 'format_version=1' "$backup/manifest.env" || fail "unsupported or invalid manifest"

  local expected path actual
  while IFS=' ' read -r expected path; do
    [[ -n "$expected" && -n "$path" ]] || fail "invalid checksum record"
    [[ -f "$backup/$path" ]] || fail "missing artifact listed in checksum: $path"
    actual="$(sha256 "$backup/$path")"
    [[ "$actual" == "$expected" ]] || fail "checksum mismatch: $path"
  done < "$backup/checksums.sha256"
  note "Backup integrity verified: $backup"
}

checksum_manifest() {
  local directory="$1"
  {
    printf '%s  %s\n' "$(sha256 "$directory/mysql.sql.gz")" "mysql.sql.gz"
    printf '%s  %s\n' "$(sha256 "$directory/redis.rdb")" "redis.rdb"
    while IFS= read -r -d '' file; do
      local relative="${file#"$directory"/}"
      printf '%s  %s\n' "$(sha256 "$file")" "$relative"
    done < <(find "$directory/minio" -type f -print0)
  } > "$directory/checksums.sha256"
}

create() {
  [[ -n "$env_file" ]] || fail "--env-file is required"
  [[ -r "$env_file" ]] || fail "cannot read env file: $env_file"
  [[ -n "$output" ]] || fail "--output is required"
  [[ -f "$compose_file" ]] || fail "compose file does not exist: $compose_file"
  if [[ "$dry_run" == true ]]; then
    note "Dry run: would validate Compose, create a MySQL logical dump, mirror MinIO, collect a Redis forensic snapshot, and write SHA-256 checksums."
    exit 0
  fi

  command -v docker >/dev/null 2>&1 || fail "docker is required"
  command -v gzip >/dev/null 2>&1 || fail "gzip is required"
  compose config --quiet

  local backup_id backup_dir redis_temp schema_version created_at
  created_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  backup_id="testhub-$(date -u +%Y%m%dT%H%M%SZ)"
  backup_dir="$output/$backup_id"
  [[ ! -e "$backup_dir" ]] || fail "backup target already exists: $backup_dir"
  mkdir -p "$backup_dir/minio"
  redis_temp="/tmp/${backup_id}.rdb"
  trap 'compose exec -T redis rm -f "$redis_temp" >/dev/null 2>&1 || true' EXIT

  note "Creating MySQL logical backup..."
  compose exec -T mysql sh -ec 'exec mysqldump --single-transaction --routines --events --triggers --set-gtid-purged=OFF -utesthub -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' | gzip -9 > "$backup_dir/mysql.sql.gz"
  schema_version="$(compose exec -T mysql sh -ec 'exec mysql -utesthub -p"$MYSQL_PASSWORD" -Nse "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1" "$MYSQL_DATABASE"' | tr -d '\r\n')"
  [[ -n "$schema_version" ]] || fail "could not read the latest successful Flyway migration"

  note "Creating Redis forensic snapshot..."
  compose exec -T redis sh -ec 'exec redis-cli -a "$TESTHUB_REDIS_PASSWORD" --rdb "$1"' sh "$redis_temp" >/dev/null
  compose cp "redis:$redis_temp" "$backup_dir/redis.rdb" >/dev/null

  note "Mirroring MinIO object storage..."
  compose run --rm --no-deps -T -v "$backup_dir/minio:/backup" --entrypoint /bin/sh minio-client -ec '
    mc alias set testhub "$TESTHUB_MINIO_ENDPOINT" "$TESTHUB_MINIO_ACCESS_KEY" "$TESTHUB_MINIO_SECRET_KEY" >/dev/null
    if mc stat "testhub/$TESTHUB_MINIO_BUCKET" >/dev/null 2>&1; then
      mc mirror --overwrite "testhub/$TESTHUB_MINIO_BUCKET" /backup
    else
      : > /backup/.empty-bucket
    fi
  '

  cat > "$backup_dir/manifest.env" <<EOF
format_version=1
backup_id=$backup_id
created_at=$created_at
flyway_version=$schema_version
database=testhub
redis_restore=flush-and-rebuild
object_storage=testhub-files
EOF
  checksum_manifest "$backup_dir"
  backup="$backup_dir"
  verify
  note "Backup complete: $backup_dir"
}

case "$action" in
  create) create ;;
  verify) verify ;;
  -h|--help|"") usage ;;
  *) fail "unknown action: $action" ;;
esac
