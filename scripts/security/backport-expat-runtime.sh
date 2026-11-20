#!/bin/sh
# Backport the merged CVE-2026-124317 bounds check to the 2.10.0 release.
set -eu
destination="${1:?Expat overlay directory required}"
patch_file="${2:?reviewed upstream patch required}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
commit=3d77567612994172f9d09b46f31bb604e16fb08d

download() {
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --retry 3 --connect-timeout 30 --max-time 180 "$1" --output "$2"
  printf '%s  %s\n' "$3" "$2" | sha256sum -c -
}
printf '%s  %s\n' 42caed50fae3f88186155b0c4280025a89913a83aed2a02986b545e0a8d1e7ed "$patch_file" | sha256sum -c -
download https://github.com/libexpat/libexpat/releases/download/R_2_10_0/expat-2.10.0.tar.xz \
  "$work/source.tar.xz" c624ba2998fc65df1163a49641ff3f9ab73138b11641d92c86bee0fb01b1bc7f
tar -xf "$work/source.tar.xz" -C "$work"
patch --batch --forward --fuzz=0 -p2 -d "$work/expat-2.10.0" -i "$patch_file"

for variant in char ushort; do
  cmake -S "$work/expat-2.10.0" -B "$work/$variant" \
    -DCMAKE_BUILD_TYPE=Release -DEXPAT_CHAR_TYPE="$variant" \
    -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_DOCS=OFF -DEXPAT_BUILD_TESTS=OFF \
    -DCMAKE_C_FLAGS='-fstack-protector-strong -D_FORTIFY_SOURCE=3 -Wformat -Werror=format-security' \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-Bsymbolic-functions,-z,relro,-z,now'
  cmake --build "$work/$variant" --parallel 2
done

# Preserve the binary ABI of both official Debian libraries.
for library in expat expatw; do
  set -- "$destination"/usr/lib/*/lib"$library".so.1.13.1
  test "$#" -eq 1
  test -f "$1"
  old="$1"
  variant=char
  if [ "$library" = expatw ]; then variant=ushort; fi
  new="$work/$variant/lib$library.so.1.13.1"
  for kind in old new; do
    if [ "$kind" = old ]; then file="$old"; else file="$new"; fi
    nm -D --defined-only "$file" | awk '{print $3}' | LC_ALL=C sort > "$work/$kind.symbols"
    readelf -d "$file" | awk '/SONAME|NEEDED/ {print $NF}' | LC_ALL=C sort > "$work/$kind.dependencies"
  done
  diff -u "$work/old.symbols" "$work/new.symbols"
  diff -u "$work/old.dependencies" "$work/new.dependencies"
  strip --strip-unneeded "$new"
  cp "$new" "$old"
done

status="$destination/var/lib/dpkg/status.d/libexpat1"
sed -i 's/^Version: 2\.10\.0-1$/Version: 2.10.0-1+testhub1/' "$status"
printf 'X-TestHub-Upstream-Commit: %s\n' "$commit" >> "$status"
cd "$destination"
while read -r checksum path; do
  md5sum "$path"
done < var/lib/dpkg/status.d/libexpat1.md5sums > "$work/md5sums"
mv "$work/md5sums" var/lib/dpkg/status.d/libexpat1.md5sums
