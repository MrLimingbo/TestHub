FROM node:22-alpine
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY services/appium-runner/package.json services/appium-runner/package.json
RUN corepack enable && pnpm install --prod --frozen-lockfile --filter @testhub/appium-runner...
COPY services/appium-runner/src services/appium-runner/src
WORKDIR /app/services/appium-runner
USER node
CMD ["node", "src/index.js"]
