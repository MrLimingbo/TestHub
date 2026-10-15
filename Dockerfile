FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN corepack enable && pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.31.3-alpine
USER root
# Apply security patches from this image's existing stable Alpine repositories.
RUN apk upgrade --no-cache
USER 101
COPY infra/nginx/production.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
