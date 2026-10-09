FROM node:22-alpine

WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY services/api-runner/package.json services/api-runner/package.json
RUN corepack enable && pnpm install --prod --frozen-lockfile --filter @testhub/api-runner...

COPY services/runner-transport services/runner-transport
COPY services/api-runner/src services/api-runner/src
WORKDIR /app/services/api-runner
USER node
CMD ["node", "src/index.js"]
