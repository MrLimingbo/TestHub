FROM golang:1.26.9-bookworm AS build-base
ARG GOPROXY=https://proxy.golang.org,direct
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOPROXY=${GOPROXY}
WORKDIR /src

# Community container images were withdrawn. Build the exact existing releases
# from their immutable upstream commits, checking the archives before compilation.
FROM build-base AS server-build
ADD --checksum=sha256:7eb30a913fea30f18069abf194e1e78e4983b558cc526911ae1c11396a9859a5 https://codeload.github.com/minio/minio/tar.gz/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 && rm /tmp/source.tar.gz
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -trimpath -ldflags='-s -w -X github.com/minio/minio/cmd.Version=2025-04-22T22:12:26Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-04-22T22-12-26Z -X github.com/minio/minio/cmd.CommitID=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e -X github.com/minio/minio/cmd.ShortCommitID=0d7408fc9969 -X github.com/minio/minio/cmd.CopyrightYear=2025' -o /out/minio . \
    && cp LICENSE /out/LICENSE

FROM build-base AS client-build
ADD --checksum=sha256:021460ac9adf491ea808e13ebb6c044d8846c175e88578798347a2ef4d361a5d https://codeload.github.com/minio/mc/tar.gz/f71ad84bcf0fd4369691952af5d925347837dcec /tmp/source.tar.gz
RUN tar -xzf /tmp/source.tar.gz --strip-components=1 && rm /tmp/source.tar.gz
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    go build -trimpath -ldflags='-s -w -X github.com/minio/mc/cmd.Version=2025-05-21T01:59:54Z -X github.com/minio/mc/cmd.ReleaseTag=RELEASE.2025-05-21T01-59-54Z -X github.com/minio/mc/cmd.CommitID=f71ad84bcf0fd4369691952af5d925347837dcec -X github.com/minio/mc/cmd.ShortCommitID=f71ad84bcf0f -X github.com/minio/mc/cmd.CopyrightYear=2025' -o /out/mc . \
    && cp LICENSE /out/LICENSE

FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

FROM runtime AS server
LABEL org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.revision="0d7408fc9969caf07de6a8c3a84f9fbb10a6739e" \
      org.opencontainers.image.version="RELEASE.2025-04-22T22-12-26Z" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"
COPY --from=server-build /out/minio /usr/local/bin/minio
COPY --from=server-build /out/LICENSE /usr/share/doc/minio/LICENSE
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]

FROM runtime AS client
LABEL org.opencontainers.image.source="https://github.com/minio/mc" \
      org.opencontainers.image.revision="f71ad84bcf0fd4369691952af5d925347837dcec" \
      org.opencontainers.image.version="RELEASE.2025-05-21T01-59-54Z" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"
COPY --from=client-build /out/mc /usr/local/bin/mc
COPY --from=client-build /out/LICENSE /usr/share/doc/minio-mc/LICENSE
ENTRYPOINT ["/usr/local/bin/mc"]
