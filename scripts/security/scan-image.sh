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

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v testhub-trivy-cache:/root/.cache/trivy \
  aquasec/trivy:0.65.0 image \
  --quiet \
  --exit-code 1 \
  --severity HIGH,CRITICAL \
  --ignore-unfixed \
  --db-repository mirror.gcr.io/aquasec/trivy-db:2 \
  --db-repository ghcr.io/aquasecurity/trivy-db:2 \
  --db-repository public.ecr.aws/aquasecurity/trivy-db:2 \
  --java-db-repository mirror.gcr.io/aquasec/trivy-java-db:1 \
  --java-db-repository ghcr.io/aquasecurity/trivy-java-db:1 \
  --java-db-repository public.ecr.aws/aquasecurity/trivy-java-db:1 \
  "$image"

printf 'Image scan passed: %s\n' "$image"
