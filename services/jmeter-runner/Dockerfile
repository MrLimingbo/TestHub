FROM node:22-alpine

ARG JMETER_VERSION=5.6.3

RUN apk add --no-cache curl openjdk17-jre-headless procps-ng tar \
  && mkdir -p /opt \
  && curl --fail --location --retry 3 --output /tmp/jmeter.tgz \
    "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
  && tar -xzf /tmp/jmeter.tgz -C /opt \
  && ln -s "/opt/apache-jmeter-${JMETER_VERSION}/bin/jmeter" /usr/local/bin/jmeter \
  && rm -f /tmp/jmeter.tgz

WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY services/api-runner/package.json services/api-runner/package.json
COPY services/jmeter-runner/package.json services/jmeter-runner/package.json
RUN corepack enable && pnpm install --prod --frozen-lockfile --filter @testhub/jmeter-runner...

COPY services/runner-transport services/runner-transport
COPY services/jmeter-runner/src services/jmeter-runner/src
WORKDIR /app/services/jmeter-runner
USER node
CMD ["node", "src/index.js"]
