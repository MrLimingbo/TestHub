#!/usr/bin/env bash
# Local equivalent of the release supply-chain gate. Run after functional test suites have passed.
set -euo pipefail

artifact_dir="${TESTHUB_SECURITY_ARTIFACT_DIR:-artifacts/security}"
platform_image="${TESTHUB_PLATFORM_IMAGE:-testhub/platform-api:release-gate}"
web_image="${TESTHUB_WEB_IMAGE:-testhub/web:release-gate}"

mkdir -p "$artifact_dir"
docker compose --env-file .env.production.example -f compose.production.yaml config --quiet
docker build -f services/platform-api/Dockerfile -t "$platform_image" .
docker build -f Dockerfile -t "$web_image" .

scripts/security/generate-sbom.sh "$platform_image" "$artifact_dir/platform-api.cdx.json"
scripts/security/generate-sbom.sh "$web_image" "$artifact_dir/web.cdx.json"
scripts/security/scan-image.sh "$platform_image"
scripts/security/scan-image.sh "$web_image"

printf 'Release supply-chain gate passed. Artifacts: %s\n' "$artifact_dir"
