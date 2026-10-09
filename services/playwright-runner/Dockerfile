FROM mcr.microsoft.com/playwright:v1.61.0-noble
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY services/playwright-runner/package.json services/playwright-runner/package.json
RUN corepack enable && pnpm install --prod --frozen-lockfile --filter @testhub/playwright-runner...
COPY services/runner-transport services/runner-transport
COPY services/playwright-runner/src services/playwright-runner/src
WORKDIR /app/services/playwright-runner
USER pwuser
CMD ["node", "src/index.js"]
