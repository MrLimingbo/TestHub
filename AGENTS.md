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

## Boundaries

- Keep the control plane modular; modules communicate through explicit application services.
- Never execute user-authored scripts inside the Spring Boot process.
- Every persisted business record must carry organization and project scope where applicable.
- Public HTTP APIs live under `/api/v1` and use the shared error envelope.
- Do not copy MeterSphere source code or visual branding.
