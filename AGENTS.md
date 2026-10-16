# TestHub Repository Guide

## Project map

- `src/`: Vue 3 control-plane UI and phase-one mock experience.
- `services/platform-api/`: Spring Boot modular monolith control plane.
- `services/api-runner/`: isolated API execution service (added in phase five).
- `docs/`: architecture decisions, API contracts, and phase reports.

## Commands

- Install: `npx pnpm@10.13.1 install`
- Develop: `npx pnpm@10.13.1 dev`
- Validate: `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`
- Isolated backend acceptance and JAR build: `JAVA_HOME=<JDK 21> node scripts/desktop/test-local-backend.mjs`. This creates a fresh H2 workspace. Do not run the full Maven test suite against the default datasource: acceptance fixtures write and delete demo data. MySQL regression must explicitly target new isolated QA infrastructure.
- Isolated MySQL/Redis/S3 acceptance: `JAVA_HOME=<JDK 21> node scripts/desktop/test-mysql-backend.mjs`. This builds the pinned MinIO source release and owns a fresh Compose project, random credentials, volumes and loopback ports; per-class schemas preserve queue-isolation assertions. See `infra/minio/README.md` for source builds and backup-client preparation.
- Runtime build/preflight: first run `npx pnpm@10.13.1 --filter @testhub/desktop exec install-electron` to download the locked native Electron binary and notices; Electron 43 uses lazy download, so package.json resolution alone is insufficient. Then run `JAVA_HOME=<native JDK 21> npx pnpm@10.13.1 desktop:prepare:local && npx pnpm@10.13.1 desktop:verify:runtime`. Prepare on each target OS/architecture; do not set `distributionReady` to true merely because an internal smoke test passed. Packaged acceptance uses `scripts/desktop/smoke-packaged.mjs` with an explicit temporary application and summary path.
- After desktop test/build validation, remove generated `.testhub/desktop-runtime/*-previous-*` runtime copies once no process uses them. Keep the current runtime; do not retain old copies after they are no longer needed.

- Desktop UI acceptance must run in the background without visible windows or focus stealing. Use the offscreen QA bootstrap described in `docs/desktop-standalone-progress-2026-10-09-mobile-sync.md`; adapt older QA launchers before running them.
- Before installing a macOS preview, verify the app extracted from the final DMG with `codesign --verify --deep --strict`; `codesign -d` metadata does not verify the resource seal. Certificate-free previews require explicit ad-hoc sealing while preserving the runtime inventory. See `docs/desktop-packaging.md` for the preview settings.
- Use isolated data for development acceptance. At the end of each approved module, stop its owned processes and remove its generated test workspaces, databases, backups, screenshots and temporary packages. Keep only sanitized acceptance summaries and reusable QA scripts; never clean user workspaces or shared services.

## Boundaries

- Keep the control plane modular; modules communicate through explicit application services.
- Never execute user-authored scripts inside the Spring Boot process.
- Every persisted business record must carry organization and project scope where applicable.
- Public HTTP APIs live under `/api/v1` and use the shared error envelope.
- Do not copy MeterSphere source code or visual branding.
