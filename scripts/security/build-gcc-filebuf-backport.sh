#!/usr/bin/env bash
# Build libstdc++ with the Debian source and library flags for the target.
set -euo pipefail
export LC_ALL=C

output="${1:?output directory required}"
patch_file="${2:?upstream patch required}"
series="${3:?Debian patch series required}"
abi_check="${4:?ABI checker required}"
architecture="$(dpkg --print-architecture)"
case "$architecture" in
    amd64)
        triplet=x86_64-linux-gnu
        series_hash=407b2e23192295142b1589c9eeb1f1670c5bf1e160e62fcfaa477510e7b4e7d3
        reference_hash=912b58566a48f860700ae033e3806d193123ed37ad4b76913edfdc37e675e403
        architecture_flags='-fcf-protection -mshstk'
        configure_flags=(--enable-cet)
        ;;
    arm64)
        triplet=aarch64-linux-gnu
        series_hash=af7c4b48653d1ee8e8fe4542ba951b69289eaaf192c810b3471518b3e6249c5a
        reference_hash=db287c065e1c93536a1260abc124792a8a3a708aedefd7a2dd8a3dcdee084d22
        architecture_flags='-mbranch-protection=standard'
        configure_flags=()
        ;;
    s390x|ppc64el|riscv64)
        series_hash=af7c4b48653d1ee8e8fe4542ba951b69289eaaf192c810b3471518b3e6249c5a
        architecture_flags=
        configure_flags=()
        case "$architecture" in
            s390x)
                triplet=s390x-linux-gnu
                reference_hash=a921af828af14955d805b57f259b6385dc4d025d28b0c7534254e5ed83de7d2f
                ;;
            ppc64el)
                triplet=powerpc64le-linux-gnu
                reference_hash=85ecb32142491ff2b6fdc4826f1a865c997eaeee81f59f5061ccd527d5f709d1
                ;;
            riscv64)
                triplet=riscv64-linux-gnu
                reference_hash=30be2870bcf5acc1b21cc45c4fd9d529abe0f86df59a1fb28af61d1829cbc73c
                ;;
        esac
        ;;
    *) printf '%s\n' 'Unsupported libstdc++ backport architecture' >&2; exit 1 ;;
esac
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

download() {
    local file="$1" hash="$2"
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
        --retry 3 --connect-timeout 30 --max-time 300 \
        "https://deb.debian.org/debian/pool/main/g/gcc-16/$file" -o "$work/$file"
    printf '%s  %s\n' "$hash" "$work/$file" | sha256sum -c -
}

printf '%s  %s\n' \
    9c7a73299ab3c13da19318d2a9f4fdb102a1fc6fae7be9d2b494fa82cd3fe767 "$patch_file" \
    "$series_hash" "$series" | sha256sum -c -
download gcc-16_16.2.0.orig.tar.gz 1f12db4797c619339c7285ce109b35888cafad2487a388430e62f03b65e9ed64
download gcc-16_16.2.0-4.debian.tar.xz f141ada9f761935a5446117393e173b71ca110ad5f73ae834362ca26d61426c4

mkdir "$work/source" "$work/obj" "$work/libgcc"
cd "$work/source"
tar -xzf "$work/gcc-16_16.2.0.orig.tar.gz" --strip-components=1
tar -xf gcc-16.2.0-dfsg.tar.xz
mv gcc-16.2.0 src
tar -xf "$work/gcc-16_16.2.0-4.debian.tar.xz"
sed 's/$/ -p1/' "$series" > debian/patches/series
QUILT_PATCHES=debian/patches QUILT_PATCH_OPTS=-E quilt --quiltrc /dev/null push -a > "$work/quilt.log" \
    || { tail -50 "$work/quilt.log" >&2; exit 1; }
(cd src/libstdc++-v3 && AUTOM4TE=/usr/bin/autom4te2.69 autoconf2.69)
patch -d src -p1 --fuzz=0 < "$patch_file"

# libgcc's gthr-default.h target normally creates this symlink in the GCC build.
ln -s "$work/source/src/libgcc/gthr-posix.h" "$work/libgcc/gthr-default.h"
cd "$work/obj"
CC=gcc-16 CXX='gcc-16 -shared-libgcc -nostdinc++' \
    CFLAGS="-g -O2 $architecture_flags" \
    CXXFLAGS="-g -O2 $architecture_flags -D_GNU_SOURCE" \
    LDFLAGS='-Wl,-z,relro' "$work/source/src/libstdc++-v3/configure" \
    --build="$triplet" --host="$triplet" --target="$triplet" \
    --prefix=/usr --libdir=/usr/lib --enable-shared --enable-nls --enable-threads=posix \
    --enable-clocale=gnu --enable-libstdcxx-time=yes --with-default-libstdcxx-abi=new \
    --enable-libstdcxx-backtrace --enable-gnu-unique-object --enable-multiarch --with-system-zlib \
    "${configure_flags[@]}" --disable-multilib --disable-libstdcxx-pch > "$work/configure.log" 2>&1 \
    || { tail -60 "$work/configure.log" >&2; exit 1; }
grep -Fxq 'checking for gthreads library... yes' "$work/configure.log"
make -j4 > "$work/build.log" 2>&1 || { tail -80 "$work/build.log" >&2; exit 1; }
if grep -q '^Module initialization function will be missing' "$work/build.log"; then
    printf '%s\n' 'C++23 module compilation failed' >&2
    exit 1
fi

library=libstdc++.so.6.0.36
reference="/usr/lib/$triplet/$library"
printf '%s  %s\n' "$reference_hash" "$reference" | sha256sum -c -
strip --strip-unneeded -o "$output/$library" "src/.libs/$library"
python3 "$abi_check" "$reference" "$output/$library"
