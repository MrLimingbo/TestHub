#!/usr/bin/env bash
# Generates the checked-in macOS and Windows installer icons from the TestHub SVG source.
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
desktop_dir="$root_dir/apps/desktop"
source_icon="$desktop_dir/build/icon.svg"
output_dir="$desktop_dir/build"
work_dir="$(mktemp -d)"
iconset_dir="$work_dir/TestHub.iconset"

cleanup() { rm -rf "$work_dir"; }
trap cleanup EXIT

command -v sips >/dev/null 2>&1 || { echo "sips is required on macOS." >&2; exit 2; }
command -v iconutil >/dev/null 2>&1 || { echo "iconutil is required on macOS." >&2; exit 2; }

sips -s format png "$source_icon" --out "$work_dir/icon_1024.png" >/dev/null
mkdir -p "$iconset_dir"
for size in 16 32 128 256 512; do
  sips -z "$size" "$size" "$work_dir/icon_1024.png" --out "$iconset_dir/icon_${size}x${size}.png" >/dev/null
  doubled_size=$((size * 2))
  sips -z "$doubled_size" "$doubled_size" "$work_dir/icon_1024.png" --out "$iconset_dir/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset_dir" -o "$output_dir/icon.icns"
sips -z 256 256 "$work_dir/icon_1024.png" -s format ico --out "$output_dir/icon.ico" >/dev/null
