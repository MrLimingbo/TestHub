# MinIO community source builds

The previously pinned community container images are no longer available. TestHub
builds the same server and backup-client releases from immutable upstream commits.
`Dockerfile` verifies each source archive with SHA-256, compiles with Go 1.26.9,
and includes the complete upstream AGPL license in the image. Source, commit and
version are recorded in OCI labels. The desktop filesystem mode does not require
these services.

| Target | Upstream source |
| --- | --- |
| `server` | [MinIO `0d7408fc9969caf07de6a8c3a84f9fbb10a6739e`](https://github.com/minio/minio/tree/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e), RELEASE.2025-04-22T22-12-26Z |
| `client` | [mc `f71ad84bcf0fd4369691952af5d925347837dcec`](https://github.com/minio/mc/tree/f71ad84bcf0fd4369691952af5d925347837dcec), RELEASE.2025-05-21T01-59-54Z |

Build the development server before starting it:

```sh
docker compose build minio
docker compose up -d --wait mysql redis minio
```

For production operations, build both images before running the existing backup
and recovery commands (use the actual configured environment file):

```sh
docker compose --env-file .env.production -f compose.production.yaml --profile operations build minio minio-client
```

Commands, volume names, S3 credentials, health probes and the backup client's
`/bin/sh` and `MC_CONFIG_DIR=/tmp/mc` behavior are preserved. The source archives
are downloaded during build; no upstream source is vendored in this repository.
Go module checksums remain enabled. Environments requiring a Go proxy can supply
the Docker build argument `GOPROXY`; the default is the official Go proxy.

For backend regression, use `JAVA_HOME=<JDK 21> node scripts/desktop/test-mysql-backend.mjs`.
It owns a random Compose project, fresh volumes, generated credentials and
ephemeral loopback ports, then removes them even when Maven fails. Test classes
using `FunctionalDatabaseProfile` receive separate schemas on this owned server.
Never run acceptance against an existing user or shared MySQL instance.
