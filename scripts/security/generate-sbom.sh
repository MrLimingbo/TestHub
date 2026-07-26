#!/usr/bin/env bash
# Produces a CycloneDX SBOM for a locally available OCI image. No registry credentials are read or written.
set -euo pipefail

usage() {
  printf '%s\n' 'Usage: scripts/security/generate-sbom.sh <image> <output-file>'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then usage; exit 0; fi
[[ $# -eq 2 ]] || { usage; exit 2; }
image="$1"
output="$2"
mkdir -p "$(dirname "$output")"
output_dir="$(cd "$(dirname "$output")" && pwd)"
output_name="$(basename "$output")"

command -v docker >/dev/null 2>&1 || { printf '%s\n' 'docker is required' >&2; exit 1; }
docker image inspect "$image" >/dev/null
temporary_output="$(mktemp "$output_dir/.${output_name}.XXXXXX")"
trap 'rm -f "$temporary_output"' EXIT

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  anchore/syft:v1.32.0 "docker:$image" -o cyclonedx-json > "$temporary_output"

[[ -s "$temporary_output" ]] || { printf '%s\n' 'SBOM generation produced no output' >&2; exit 1; }
mv "$temporary_output" "$output_dir/$output_name"
trap - EXIT
printf 'SBOM written: %s\n' "$output_dir/$output_name"
