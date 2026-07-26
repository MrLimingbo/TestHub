#!/usr/bin/env bash
# Validates the chart without requiring Helm on the host or a Kubernetes cluster.
set -euo pipefail

chart_dir="infra/helm/testhub"
helm_image="${TESTHUB_HELM_VALIDATION_IMAGE:-alpine/helm:3.17.3}"

[[ -d "$chart_dir" ]] || { printf 'Error: chart directory does not exist: %s\n' "$chart_dir" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { printf 'Error: docker is required\n' >&2; exit 2; }

docker run --rm -v "$PWD:/workspace:ro" -w /workspace "$helm_image" lint "$chart_dir"
docker run --rm -v "$PWD:/workspace:ro" -w /workspace "$helm_image" template testhub "$chart_dir" \
  --namespace testhub --values "$chart_dir/ci-values.yaml" >/dev/null
printf 'Helm chart lint and template validation passed.\n'
