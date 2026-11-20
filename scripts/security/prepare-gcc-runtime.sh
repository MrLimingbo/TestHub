#!/bin/sh
# Pinned Debian GCC runtime bundle. No compiler is installed in the runtime.
set -eu
arch="${1:?target architecture required}"
destination="${2:?empty output directory required}"
baseline="${3:?baseline library directory required}"
backport="${4:-}"
version=16.2.0-4
cxx_elf_hash=
case "$arch" in
  amd64)
    base_hash=b81ebc677d1e2c4c0db8b3968f29addc18839d173a35366958fa815f3c5d1184
    gcc_hash=d251ea642c3795e8723ac2b9eeebe511467ca04590ba70f3d555a7616787d7e3
    cxx_hash=cef6654bba1daece0bc0dbe0e3f69fa5a26eb6ba139f3536e6f4806bd787f3e7
    triplet=x86_64-linux-gnu
    cxx_elf_hash=912b58566a48f860700ae033e3806d193123ed37ad4b76913edfdc37e675e403
    ;;
  arm64)
    base_hash=4e4a72005f1fb7eaec947f98890cde9d02247ef33454aa6e90e29a31bcc680a4
    gcc_hash=385ea4394ef27e610dfa033ea422dd79b154d88255a9d945de86ece751578b9f
    cxx_hash=d7d0387468ffed2bc8aa32e16cd05bd9e4b9ea120863d69ee557d2d6dd663c39
    triplet=aarch64-linux-gnu
    cxx_elf_hash=db287c065e1c93536a1260abc124792a8a3a708aedefd7a2dd8a3dcdee084d22
    ;;
  s390x)
    base_hash=90f40ecd3d35a35fce805dcad62ff10cbc3e08eac41a38c4792ef2199a82ce04
    gcc_hash=90ff74e24b909e475303273385eddedf97b7ca4f107f732ea456ec71fdeaaac9
    cxx_hash=38583608112113b93aa35948097da9a8a2ae9ef4c36877c09f0b7fa16530971d
    triplet=s390x-linux-gnu
    cxx_elf_hash=a921af828af14955d805b57f259b6385dc4d025d28b0c7534254e5ed83de7d2f
    ;;
  ppc64le)
    arch=ppc64el
    base_hash=549465b2ed9f2b441b1db7bb2aa013f41f479d253f1603f76da7b38a1ba9ae1c
    gcc_hash=3daa9541e302733afdf92b2f0aaa5f3e48aa7baf38aa7db09c74d5f71ca1b0fa
    cxx_hash=e90a1a81aaff85f08f013b812c3fb8cdf12aa366237f62a5e687d0e31e369c90
    triplet=powerpc64le-linux-gnu
    cxx_elf_hash=85ecb32142491ff2b6fdc4826f1a865c997eaeee81f59f5061ccd527d5f709d1
    ;;
  riscv64)
    base_hash=06767e7470cb7491cda9173670f4b6c75065441fe532cacfa87c04a3acae57eb
    gcc_hash=053982cfb062799978b2b843ca7c2bcc321761e36a82ffe038d7460b4b229dd8
    cxx_hash=ca46986768b91156b89d1a17f65cd54a7708bc72128f01d37df717f1385ba1ef
    triplet=riscv64-linux-gnu
    cxx_elf_hash=30be2870bcf5acc1b21cc45c4fd9d529abe0f86df59a1fb28af61d1829cbc73c
    ;;
  *) printf 'Unsupported GCC target architecture: %s\n' "$arch" >&2; exit 1 ;;
esac
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for package in gcc-16-base libgcc-s1 libstdc++6; do
  case "$package" in
    gcc-16-base) hash="$base_hash" ;;
    libgcc-s1) hash="$gcc_hash" ;;
    libstdc++6) hash="$cxx_hash" ;;
  esac
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --retry 3 --connect-timeout 30 --max-time 180 \
    "https://deb.debian.org/debian/pool/main/g/gcc-16/${package}_${version}_${arch}.deb" --output "$work/package.deb"
  printf '%s  %s\n' "$hash" "$work/package.deb" | sha256sum -c -
  test "$(dpkg-deb --field "$work/package.deb" Package)" = "$package"
  test "$(dpkg-deb --field "$work/package.deb" Version)" = "$version"
  test "$(dpkg-deb --field "$work/package.deb" Architecture)" = "$arch"
  if [ "$package" = gcc-16-base ]; then mkdir "$destination"; fi
  dpkg-deb --extract "$work/package.deb" "$destination"
  rm -rf "$work/control"
  dpkg-deb --control "$work/package.deb" "$work/control"
  mkdir -p "$destination/var/lib/dpkg/status.d"
  cp "$work/control/control" "$destination/var/lib/dpkg/status.d/$package"
  cp "$work/control/md5sums" "$destination/var/lib/dpkg/status.d/$package.md5sums"
done

if [ -n "$backport" ] && [ -n "$cxx_elf_hash" ]; then
  library="usr/lib/$triplet/libstdc++.so.6.0.36"
  printf '%s  %s\n' "$cxx_elf_hash" "$destination/$library" | sha256sum -c -
  test -s "$backport/${library##*/}"
  install -m 644 "$backport/${library##*/}" "$destination/$library"
  gcc_status="$destination/var/lib/dpkg/status.d/libstdc++6"
  grep -qx 'Version: 16.2.0-4' "$gcc_status"
  sed -i 's/^Version: 16\.2\.0-4$/Version: 16.2.0-4+testhub1/' "$gcc_status"
  printf '%s\n' 'X-TestHub-Upstream-Commit: 68b1b9056c70acb31f2964963b51c480ff820602' >> "$gcc_status"
  hash="$(md5sum "$destination/$library")"
  sums="$gcc_status.md5sums"
  awk -v file="$library" -v hash="${hash%% *}" '
    $2 == file { print hash "  " file; found++; next }
    { print }
    END { if (found != 1) exit 1 }
  ' "$sums" > "$work/libstdcxx.md5sums"
  mv "$work/libstdcxx.md5sums" "$sums"
fi

for library in libgcc_s.so.1 'libstdc++.so.6.*'; do
  set -- "$baseline"/$library
  test "$#" -eq 1
  old="$1"
  set -- "$destination"/usr/lib/*/$library
  test "$#" -eq 1
  new="$1"
  for kind in old new; do
    if [ "$kind" = old ]; then file="$old"; else file="$new"; fi
    nm -D --defined-only "$file" | awk '{gsub(/@@/, "@", $3); print $3}' | LC_ALL=C sort -u > "$work/$kind.symbols"
    readelf -d "$file" | awk '/SONAME/ {print $NF}' > "$work/$kind.soname"
  done
  comm -23 "$work/old.symbols" "$work/new.symbols" > "$work/removed"
  test ! -s "$work/removed" || { cat "$work/removed" >&2; exit 1; }
  diff -u "$work/old.soname" "$work/new.soname"
  required="$(readelf --version-info "$new" | sed -n 's/.*Name: GLIBC_\([0-9.]*\).*/\1/p' | sort -V | tail -1)"
  test -n "$required"
  dpkg --compare-versions "$required" le 2.41
done
