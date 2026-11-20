#!/bin/sh
# Temporary, checksum-pinned Debian package overlay until Debian 13 backports
# Expat 2.10.0. Keep the real package metadata for SBOM/vulnerability scanners.
set -eu

arch="${1:?target architecture required}"
destination="${2:?empty output directory required}"
case "$arch" in
  amd64) hash=9a333cf590099569f577c1a1d47ac1f439eab14c1faff09b7e81ce3802938003 ;;
  arm64) hash=85b512bb1342e2321c468d89476beba4b2077d75b3fb48348334408679e408d5 ;;
  s390x) hash=575a6de093c09deeac6881125a8223672fa747b7e909613a952a0562d18aba83 ;;
  ppc64le) arch=ppc64el; hash=2ce08288f5831934b039725adbe9e383536d0d7a0e62193a875854deb904a769 ;;
  riscv64) hash=2982bc81914d88849afe800c5498f7230fb9820f085ba3ac4c2cd7aeec37d93a ;;
  *) printf 'Unsupported Expat target architecture: %s\n' "$arch" >&2; exit 1 ;;
esac

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT HUP INT TERM
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
  --retry 3 --connect-timeout 30 --max-time 180 \
  "https://deb.debian.org/debian/pool/main/e/expat/libexpat1_2.10.0-1_${arch}.deb" \
  --output "$work/expat.deb"
printf '%s  %s\n' "$hash" "$work/expat.deb" | sha256sum -c - >/dev/null
test "$(dpkg-deb --field "$work/expat.deb" Package)" = libexpat1
test "$(dpkg-deb --field "$work/expat.deb" Version)" = 2.10.0-1
test "$(dpkg-deb --field "$work/expat.deb" Architecture)" = "$arch"
mkdir "$destination"
dpkg-deb --extract "$work/expat.deb" "$destination"
dpkg-deb --control "$work/expat.deb" "$work/control"
mkdir -p "$destination/var/lib/dpkg/status.d"
cp "$work/control/control" "$destination/var/lib/dpkg/status.d/libexpat1"
cp "$work/control/md5sums" "$destination/var/lib/dpkg/status.d/libexpat1.md5sums"
