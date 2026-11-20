#!/bin/sh
# Install only the Debian library package fixed for CVE-2026-95512.
set -eu
arch="${1:?target architecture required}"
destination="${2:?empty output directory required}"
version=2.14.3+dfsg-3
case "$arch" in
  amd64) hash=f6eaffc0ddbd5df88bf08c5a01ba85e3bc83bf36c8afcc0563213764b7cee445 ;;
  arm64) hash=f94638daec4e7c0de4c8cbf7cb68517e640b5a3a424fb840cd9fd43a24a5b939 ;;
  s390x) hash=de72d9940ea3c3cbadf213f7824f4d554159164f128e6be5cb40a02434c8d638 ;;
  ppc64le) arch=ppc64el; hash=037def9a1642be94f15a809d00f35c477d358ca30ec1ba075bd8c0f3e2ba61f1 ;;
  riscv64) hash=e898c05a499d73f766181902c47af648ff2f328f40e57cb01105a707e951c669 ;;
  *) printf 'Unsupported FreeType target architecture: %s\n' "$arch" >&2; exit 1 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
  --retry 3 --connect-timeout 30 --max-time 180 \
  "https://deb.debian.org/debian/pool/main/f/freetype/libfreetype6_${version}_${arch}.deb" \
  --output "$work/freetype.deb"
printf '%s  %s\n' "$hash" "$work/freetype.deb" | sha256sum -c -
test "$(dpkg-deb --field "$work/freetype.deb" Package)" = libfreetype6
test "$(dpkg-deb --field "$work/freetype.deb" Version)" = "$version"
test "$(dpkg-deb --field "$work/freetype.deb" Architecture)" = "$arch"
mkdir "$destination"
dpkg-deb --extract "$work/freetype.deb" "$destination"
dpkg-deb --control "$work/freetype.deb" "$work/control"
mkdir -p "$destination/var/lib/dpkg/status.d"
cp "$work/control/control" "$destination/var/lib/dpkg/status.d/libfreetype6"
cp "$work/control/md5sums" "$destination/var/lib/dpkg/status.d/libfreetype6.md5sums"
