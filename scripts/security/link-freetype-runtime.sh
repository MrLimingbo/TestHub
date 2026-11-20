#!/busybox sh
set -eu
/busybox grep -qx 'Version: 2.13.3+dfsg-1+deb13u1' /var/lib/dpkg/status.d/libfreetype6
set -- /usr/lib/*/libfreetype.so.6
test "$#" -eq 1
system_library="$1"
set -- /usr/lib/jvm/temurin-21-jre-*/lib/libfreetype.so
test "$#" -eq 1
test -f "$1"
/busybox rm "$system_library" /usr/lib/*/libfreetype.so.6.20.2 "$1"
/busybox ln -s "$system_library" "$1"
