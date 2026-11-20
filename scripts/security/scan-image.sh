#!/usr/bin/env bash
# Fails when Trivy finds a fixed HIGH or CRITICAL vulnerability in a local OCI image.
set -euo pipefail

usage() {
  printf '%s\n' 'Usage: scripts/security/scan-image.sh <image>'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 1 ]] || { usage; exit 2; }
image="$1"

command -v docker >/dev/null 2>&1 || { printf '%s\n' 'docker is required' >&2; exit 1; }
docker image inspect "$image" >/dev/null

db_options=(--db-repository mirror.gcr.io/aquasec/trivy-db:2 --db-repository ghcr.io/aquasecurity/trivy-db:2 --db-repository public.ecr.aws/aquasecurity/trivy-db:2)
java_db_options=(--java-db-repository mirror.gcr.io/aquasec/trivy-java-db:1 --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db:1)
# Trivy only falls back for selected registry errors, not every network failure.
if [[ -n "${TESTHUB_TRIVY_DB_REPOSITORY:-}" ]]; then db_options=(--db-repository "$TESTHUB_TRIVY_DB_REPOSITORY"); fi
if [[ -n "${TESTHUB_TRIVY_JAVA_DB_REPOSITORY:-}" ]]; then java_db_options=(--java-db-repository "$TESTHUB_TRIVY_JAVA_DB_REPOSITORY"); fi
docker_options=(run --rm)
if [[ -n "${TESTHUB_SECURITY_HTTPS_PROXY:-}" ]]; then
  export HTTPS_PROXY="$TESTHUB_SECURITY_HTTPS_PROXY"
  # Forward by environment name so proxy credentials never appear in command arguments.
  docker_options+=(--env HTTPS_PROXY)
fi

docker "${docker_options[@]}" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v testhub-trivy-cache:/root/.cache/trivy \
  aquasec/trivy:0.65.0 image \
  --quiet \
  --timeout "${TESTHUB_TRIVY_TIMEOUT:-5m}" \
  --exit-code 1 \
  --severity HIGH,CRITICAL \
  --ignore-unfixed \
  "${db_options[@]}" "${java_db_options[@]}" \
  "$image"

printf 'Image scan passed: %s\n' "$image"
