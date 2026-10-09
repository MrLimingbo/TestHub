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
- After desktop test/build validation, remove generated `.testhub/desktop-runtime/*-previous-*` runtime copies once no process uses them. Keep the current runtime; do not retain old copies after they are no longer needed.

- Desktop UI acceptance must run in the background without visible windows or focus stealing. Use the offscreen QA bootstrap described in `docs/desktop-standalone-progress-2026-10-09-mobile-sync.md`; adapt older QA launchers before running them.

## Boundaries

- Keep the control plane modular; modules communicate through explicit application services.
- Never execute user-authored scripts inside the Spring Boot process.
- Every persisted business record must carry organization and project scope where applicable.
- Public HTTP APIs live under `/api/v1` and use the shared error envelope.
- Do not copy MeterSphere source code or visual branding.
