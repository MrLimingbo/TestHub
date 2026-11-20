# The application JAR contains Java bytecode and all bundled JNI variants.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY services/platform-api/pom.xml services/platform-api/pom.xml
COPY services/platform-api/src services/platform-api/src
RUN mvn -q -pl services/platform-api -am package -DskipTests

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:d978eefd303c59c42f382aef811a0e41f2c68a6e5c319a23769cd3d1d5bf8e3c AS runtime-base

# Package the backport only after comparison with the exact Debian runtime.
FROM debian:forky-slim@sha256:dd5fe0b4f8959afea87fe8d04cd7766aa5905476730607f3d7efacf80552edd3 AS gcc-filebuf-backport
ARG TARGETARCH
COPY --from=runtime-base /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY scripts/security/build-gcc-filebuf-backport.sh scripts/security/check-gcc-filebuf-abi.py /native-build/
COPY infra/native-patches/libstdcxx-CVE-2026-125709.patch infra/native-patches/gcc-16.2.0-4-*.series /native-build/
RUN --mount=type=tmpfs,target=/tmp mkdir /out \
    && case "$TARGETARCH" in amd64|arm64|s390x|ppc64le|riscv64) \
        set -- systemtap-sdt-dev=5.6-1; \
        series=arm64; \
        if [ "$TARGETARCH" = amd64 ]; then series=amd64; fi; \
        if [ "$TARGETARCH" = arm64 ]; then set --; fi; \
        sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
        && printf '%s\n' 'Acquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";' 'Acquire::Retries "3";' >/etc/apt/apt.conf.d/90qa \
        && apt-get update \
        && apt-get install -y --no-install-recommends g++-16=16.2.0-4 gcc-16=16.2.0-4 libstdc++6=16.2.0-4 \
             binutils=2.47.50.20261021-1 libc6-dev=2.43-7 autoconf2.69 quilt xz-utils make patch gettext python3 curl "$@" \
        && /native-build/build-gcc-filebuf-backport.sh /out /native-build/libstdcxx-CVE-2026-125709.patch \
             "/native-build/gcc-16.2.0-4-$series.series" /native-build/check-gcc-filebuf-abi.py \
        && rm -rf /var/lib/apt/lists/* \
        ;; esac

FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2 AS runtime-patches
COPY --from=runtime-base /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt update \
    && apt-get -o Acquire::Retries=3 -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt install -y --no-install-recommends \
    ca-certificates curl cmake make patch xz-utils gcc libc6-dev binutils \
    && rm -rf /var/lib/apt/lists/*
ARG TARGETARCH
COPY scripts/security/prepare-expat-runtime.sh /prepare-expat-runtime.sh
COPY scripts/security/backport-expat-runtime.sh scripts/security/prepare-freetype-runtime.sh scripts/security/link-freetype-runtime.sh /
COPY infra/native-patches/expat-CVE-2026-124317.patch /expat-bounds.patch
COPY scripts/security/prepare-gcc-runtime.sh /prepare-gcc-runtime.sh
COPY --from=runtime-base /usr/lib/*/libstdc++.so.6.* /baseline/
COPY --from=runtime-base /usr/lib/*/libgcc_s.so.1 /baseline/
COPY services/platform-api/runtime-guard.c /runtime-guard.c
RUN sh /prepare-expat-runtime.sh "$TARGETARCH" /expat-root \
    && sh /backport-expat-runtime.sh /expat-root /expat-bounds.patch \
    && sh /prepare-freetype-runtime.sh "$TARGETARCH" /freetype-root
RUN --mount=from=gcc-filebuf-backport,source=/out,target=/gcc-filebuf \
    sh /prepare-gcc-runtime.sh "$TARGETARCH" /gcc-root /baseline /gcc-filebuf
RUN gcc -O2 -Wall -Wextra -Werror -fstack-protector-strong -D_FORTIFY_SOURCE=3 \
    -Wl,-z,relro,-z,now /runtime-guard.c -o /testhub-runtime-guard

# Backport the resolver fix into the pinned Debian libc and matching loader.
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2 AS glibc-dns-backport
ARG TARGETARCH
COPY --from=runtime-base /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY scripts/security/prepare-glibc-dns-runtime.sh scripts/security/check-glibc-dns-abi.py scripts/security/check-gcc-filebuf-abi.py /native-build/
COPY infra/native-patches/glibc-CVE-2026-8674.patch /native-build/
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && printf '%s\n' 'Acquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";' 'Acquire::Retries "3";' >/etc/apt/apt.conf.d/90qa \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
         gcc-14=14.2.0-19 gcc make dpkg-dev debhelper quilt gawk bison gperf file symlinks netbase \
         libaudit-dev libcap-dev libselinux1-dev gettext python3 linux-libc-dev rdfind patch binutils \
         xz-utils autoconf curl \
    && rm -rf /var/lib/apt/lists/*
RUN --mount=type=tmpfs,target=/tmp --mount=from=runtime-base,source=/,target=/baseline,readonly \
    sh /native-build/prepare-glibc-dns-runtime.sh "$TARGETARCH" /glibc-root /baseline \
       /native-build/glibc-CVE-2026-8674.patch /native-build/check-glibc-dns-abi.py

FROM busybox:1.37.0-musl@sha256:f6a95d611e0a8780fabdb2aec43e31275b2720dfefa3e41b16835bfc42454478 AS runtime-tools

FROM runtime-base
# Fail closed when the pinned base changes; review whether the overlay is still
# needed. The static helper is a build-only mount and is absent at runtime.
USER root
ARG TARGETARCH
# The pinned ppc64le image uses Debian's ppc64el package directory.
RUN --mount=from=runtime-tools,source=/bin/busybox,target=/busybox ["/busybox", "sh", "-ec", "if [ \"$TARGETARCH\" = ppc64le ] && [ ! -x /usr/bin/java ]; then test \"$(/busybox readlink /usr/bin/java)\" = /usr/lib/jvm/temurin-21-jre-ppc64le/bin/java; test -x /usr/lib/jvm/temurin-21-jre-ppc64el/bin/java; /busybox ln -sfn /usr/lib/jvm/temurin-21-jre-ppc64el/bin/java /usr/bin/java; fi"]
RUN --mount=from=runtime-tools,source=/bin/busybox,target=/busybox ["/busybox", "sh", "-ec", "/busybox grep -qx 'Version: 2.9.0-1~deb13u1' /var/lib/dpkg/status.d/libexpat1; /busybox rm /usr/lib/*/libexpat.so.1 /usr/lib/*/libexpat.so.1.13.0 /usr/lib/*/libexpatw.so.1 /usr/lib/*/libexpatw.so.1.13.0"]
RUN --mount=from=runtime-tools,source=/bin/busybox,target=/busybox --mount=from=runtime-patches,source=/link-freetype-runtime.sh,target=/overlay.sh ["/busybox", "sh", "/overlay.sh"]
COPY --from=runtime-patches /expat-root/ /
COPY --from=runtime-patches /freetype-root/ /
RUN --mount=from=runtime-tools,source=/bin/busybox,target=/busybox ["/busybox", "sh", "-ec", "/busybox grep -qx 'Version: 14.2.0-19' /var/lib/dpkg/status.d/libstdc++6; /busybox rm /usr/lib/*/libstdc++.so.6 /usr/lib/*/libstdc++.so.6.0.33 /var/lib/dpkg/status.d/gcc-14-base /var/lib/dpkg/status.d/gcc-14-base.md5sums; /busybox rm -r /usr/share/doc/gcc-14-base"]
COPY --from=runtime-patches /gcc-root/ /
COPY --from=glibc-dns-backport /glibc-root/ /
WORKDIR /app
COPY --from=runtime-patches /testhub-runtime-guard /app/testhub-runtime-guard
COPY --from=build --chown=nonroot:nonroot /workspace/services/platform-api/target/platform-api-0.1.0-SNAPSHOT.jar /app/testhub-platform-api.jar
USER nonroot
EXPOSE 8080
ENTRYPOINT ["/app/testhub-runtime-guard", "java", "-XX:MaxRAMPercentage=75", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/testhub-platform-api.jar"]
