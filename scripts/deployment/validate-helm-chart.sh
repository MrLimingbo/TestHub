#!/usr/bin/env bash
# Validates the chart without requiring Helm on the host or a Kubernetes cluster.
set -euo pipefail

chart_dir="infra/helm/testhub"
helm_image="${TESTHUB_HELM_VALIDATION_IMAGE:-alpine/helm:3.17.3}"

[[ -d "$chart_dir" ]] || { printf 'Error: chart directory does not exist: %s\n' "$chart_dir" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { printf 'Error: docker is required\n' >&2; exit 2; }

helm=(docker run --rm -v "$PWD:/workspace:ro" -w /workspace "$helm_image")
render=("${helm[@]}" template testhub "$chart_dir" --namespace testhub --values "$chart_dir/ci-values.yaml")
"${helm[@]}" lint "$chart_dir"
default_rendered="$("${render[@]}")"
if grep -Fq 'name: LOCALDOMAIN' <<<"$default_rendered"; then
  printf 'Default deployment should inherit the resolver search list.\n' >&2
  exit 1
fi
dns_rendered="$("${render[@]}" --set-string 'api.dnsSearchDomains=team.svc.cluster.local svc.cluster.local cluster.local')"
grep -Fq 'name: LOCALDOMAIN' <<<"$dns_rendered"
grep -Fq 'value: "team.svc.cluster.local svc.cluster.local cluster.local"' <<<"$dns_rendered"

label="$(printf 'a%062d' 0)"
boundary="$label $label $label $label"
"${render[@]}" --set-string "api.dnsSearchDomains=  $boundary  " >/dev/null
ascii="$(printf 'a%.0s' {1..254})"
for invalid in "$boundary x" "${ascii}é" $'team.test\ncluster.local'; do
  if error="$("${render[@]}" --set-string "api.dnsSearchDomains=$invalid" 2>&1)"; then
    printf 'Helm accepted an invalid DNS search list.\n' >&2
    exit 1
  fi
  grep -Fq 'api.dnsSearchDomains must be single-line and at most 255 bytes after whitespace normalization' <<<"$error"
done
printf 'Helm chart lint and template validation passed.\n'
