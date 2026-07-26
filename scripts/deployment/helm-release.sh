#!/usr/bin/env bash
# Plans, applies, or rolls back a TestHub Helm release with explicit operator confirmation.
set -euo pipefail
umask 077

usage() {
  cat <<'EOF'
Usage:
  scripts/deployment/helm-release.sh plan --release NAME --namespace NAME --values FILE [--chart DIR] [--timeout 10m]
  scripts/deployment/helm-release.sh apply --release NAME --namespace NAME --values FILE --approve APPLY_TESTHUB_HELM_RELEASE [--chart DIR] [--timeout 10m]
  scripts/deployment/helm-release.sh rollback --release NAME --namespace NAME --revision NUMBER --approve ROLLBACK_TESTHUB_HELM_RELEASE [--timeout 10m]

plan is client-side only. apply creates or upgrades a release with --atomic and --wait.
rollback changes Kubernetes workloads only; it cannot reverse Flyway database migrations or restore data.
EOF
}

fail() { printf 'Error: %s\n' "$*" >&2; exit 2; }
require_value() { [[ -n "$2" ]] || fail "$1 is required"; }

action="${1:-}"
[[ $# -gt 0 ]] && shift
release=""
namespace=""
values=""
chart="infra/helm/testhub"
timeout="10m"
revision=""
approval=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) release="${2:-}"; shift 2 ;;
    --namespace) namespace="${2:-}"; shift 2 ;;
    --values) values="${2:-}"; shift 2 ;;
    --chart) chart="${2:-}"; shift 2 ;;
    --timeout) timeout="${2:-}"; shift 2 ;;
    --revision) revision="${2:-}"; shift 2 ;;
    --approve) approval="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown option: $1" ;;
  esac
done

case "$action" in
  plan|apply|rollback) ;;
  -h|--help|"") usage; exit 0 ;;
  *) fail "unknown command: $action" ;;
esac

command -v helm >/dev/null 2>&1 || fail "helm is required; use scripts/deployment/validate-helm-chart.sh for containerized linting"
require_value "--release" "$release"
require_value "--namespace" "$namespace"
[[ "$release" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "--release must be a DNS label"
[[ "$namespace" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || fail "--namespace must be a DNS label"

case "$action" in
  plan)
    require_value "--values" "$values"
    [[ -r "$values" ]] || fail "cannot read values file: $values"
    [[ -d "$chart" ]] || fail "chart directory does not exist: $chart"
    helm upgrade --install "$release" "$chart" --namespace "$namespace" --values "$values" --dry-run=client --debug
    ;;
  apply)
    require_value "--values" "$values"
    [[ -r "$values" ]] || fail "cannot read values file: $values"
    [[ -d "$chart" ]] || fail "chart directory does not exist: $chart"
    [[ "$approval" == "APPLY_TESTHUB_HELM_RELEASE" ]] || fail "apply requires --approve APPLY_TESTHUB_HELM_RELEASE"
    helm upgrade --install "$release" "$chart" --namespace "$namespace" --create-namespace --values "$values" \
      --atomic --wait --timeout "$timeout" --history-max 10
    ;;
  rollback)
    [[ "$revision" =~ ^[1-9][0-9]*$ ]] || fail "rollback requires a positive --revision"
    [[ "$approval" == "ROLLBACK_TESTHUB_HELM_RELEASE" ]] || fail "rollback requires --approve ROLLBACK_TESTHUB_HELM_RELEASE"
    helm rollback "$release" "$revision" --namespace "$namespace" --wait --timeout "$timeout" --cleanup-on-fail
    ;;
esac
