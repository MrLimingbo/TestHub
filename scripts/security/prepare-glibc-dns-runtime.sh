#!/bin/sh
set -eu

arch="${1:?target architecture required}"
output="${2:?output directory required}"
baseline="${3:?baseline mount required}"
patch_file="${4:?patch path required}"
abi_checker="${5:?ABI checker required}"
version=2.41-12+deb13u4
commit=506ea57086bfb9ce3daff1c14246a1cb532aba0a
case "$arch" in
  amd64) package_hash=967aa62605721081c3eb2a17650611a792aa802d76a6511d1840242623d204c9; loader=ld-linux-x86-64.so.2 ;;
  arm64) package_hash=8784eda966b189c777a384dac5ce009e8fc9b52d006926c5a013e7fa8aa688cc; loader=ld-linux-aarch64.so.1 ;;
  s390x) package_hash=c6e658d8e17d885c831ad1275501ef1abdadc6c93e9ac7758cfd1e5cde2d608b; loader=ld64.so.1 ;;
  ppc64le) arch=ppc64el; package_hash=917cbd81a9038133bd58bbfe0991db889a78eb446be2080015b7804942b45ec2; loader=ld64.so.2 ;;
  riscv64) package_hash=77f0da760da3eab6318917857669ec23556b4e1ee489218cb966884a11b0d967; loader=ld-linux-riscv64-lp64d.so.1 ;;
  *) printf 'Unsupported glibc target architecture: %s\n' "$arch" >&2; exit 1 ;;
esac
test "$(dpkg --print-architecture)" = "$arch"
triplet="$(dpkg-architecture -qDEB_HOST_GNU_TYPE)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
cd "$work"
fetch() {
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --retry 3 --connect-timeout 30 --max-time 240 \
    "https://deb.debian.org/debian/pool/main/g/glibc/$1" --output "$1"
  printf '%s  %s\n' "$2" "$1" | sha256sum -c -
}

printf '%s  %s\n' 4fb6cd7c31aac9c41379bcec30c22dc537e726dd81c349670b818db5600abaac "$patch_file" | sha256sum -c -
deb="libc6_${version}_$arch.deb"
fetch "$deb" "$package_hash"
fetch "glibc_$version.dsc" 0915324aa646bb99abfaa9aa6ecb734b30babd28454a3e51f3152912370fbd3e
fetch glibc_2.41.orig.tar.xz f24aa441021121a79266f0d75242706cab8843a47901fefe74527491807f1998
fetch "glibc_$version.debian.tar.xz" dda4153511bfd543502d18e5bc9323110996fe9c531f14ae3fad6eb17f027c7d

test "$(dpkg-deb --field "$deb" Package)" = libc6
test "$(dpkg-deb --field "$deb" Version)" = "$version"
test "$(dpkg-deb --field "$deb" Architecture)" = "$arch"
dpkg-deb --raw-extract "$deb" original
metadata=var/lib/dpkg/status.d
grep -qx "Version: $version" "$baseline/$metadata/libc6"
cmp original/DEBIAN/md5sums "$baseline/$metadata/libc6.md5sums"
libdir="usr/lib/$triplet"
for library in libc.so.6 "$loader"; do
  cmp "original/$libdir/$library" "$baseline/$libdir/$library"
done

dpkg-source --no-check -x "glibc_$version.dsc"
cd glibc-2.41
patch --batch --forward --fuzz=0 -p1 < "$patch_file"
export DEB_BUILD_OPTIONS="nocheck parallel=6" DEB_BUILD_PROFILES=nobiarch
debian/rules build_libc LIBGD=no > "$work/build.log" 2>&1 || {
  tail -80 "$work/build.log" >&2
  exit 1
}
build="$work/glibc-2.41/build-tree/$arch-libc"
strip --strip-unneeded -o "$work/libc.so.6" "$build/libc.so"
strip --strip-unneeded -o "$work/$loader" "$build/elf/ld.so"

cd "$work"
for library in libc.so.6 "$loader"; do
  python3 "$abi_checker" "original/$libdir/$library" "$work/$library"
  mode="$(stat -c %a "original/$libdir/$library")"
  install -m "$mode" "$work/$library" "original/$libdir/$library"
done

python3 - "$version" "$commit" "$libdir/libc.so.6" "$libdir/$loader" <<'PY'
import hashlib
from pathlib import Path
import sys

version, commit, *paths = sys.argv[1:]
package = Path("original")
control = package / "DEBIAN/control"
record = control.read_text()
old_version = f"Version: {version}\n"
if record.count(old_version) != 1:
    raise SystemExit("Unexpected libc6 version field")
control.write_text(record.replace(old_version, f"Version: {version}+testhub1\n", 1)
                   + f"X-TestHub-Upstream-Commit: {commit}\n")
md5sums = package / "DEBIAN/md5sums"
rows = [line.split() for line in md5sums.read_text().splitlines()]
for path in paths:
    [entry] = [row for row in rows if row[1] == path]
    entry[0] = hashlib.md5((package / path).read_bytes()).hexdigest()
md5sums.write_text("".join(f"{checksum}  {name}\n" for checksum, name in rows))
PY

(cd original && md5sum --status -c DEBIAN/md5sums)
dpkg-deb --root-owner-group --build original libc6-testhub.deb
dpkg-deb --extract libc6-testhub.deb verified
dpkg-deb --control libc6-testhub.deb control
(cd verified && md5sum --status -c "$work/control/md5sums")
mkdir -p "$output/$libdir" "$output/$metadata"
cp -p "verified/$libdir/libc.so.6" "verified/$libdir/$loader" "$output/$libdir/"
cp control/control "$output/$metadata/libc6"
cp control/md5sums "$output/$metadata/libc6.md5sums"
