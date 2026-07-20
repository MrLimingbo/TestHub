# 接口自动化 CLI、CI 与签名 Webhook

本接口用于触发已保存的接口自动化场景。它只会把任务写入控制面 Outbox；Runner 仍需通过现有可信校验、显式启用和网络边界检查后，才可能执行受限 HTTP 请求。

## CLI：项目访问令牌

在“项目设置 / 成员与权限”创建仅含 `run:execute` 的项目访问令牌。若还需要读取报告，同时授予 `project:read`。不要把令牌提交到源码、日志或 CI 输出。

仓库内置零依赖的 `@testhub/cli`，要求 Node.js 22+。它从命令行参数或 `TESTHUB_*` 环境变量读取配置；令牌仅进入 `Authorization` 请求头，命令输出永不回显令牌。

```bash
export TESTHUB_URL='https://testhub.example.com'
export TESTHUB_PROJECT_ID='01...'
export TESTHUB_SCENARIO_ID='01...'
export TESTHUB_TOKEN='thp_...'

node services/testhub-cli/src/index.js execution run \
  --timeout 300 --wait-timeout 900 --poll-interval 5 \
  --report-output './artifacts/testhub-execution-report.json'
```

`execution run` 会提交场景、等待终态，并在可选的 `--report-output` 存在时校验后原子归档报告。它只输出一份 JSON：提交摘要、终态摘要和报告工件元数据。业务失败仍会先归档脱敏报告，再以退出码 `2` 结束流水线；客户端等待超时不会下载可能尚未完成的报告。

也可以使用拆分命令，便于在不同流水线阶段间传递执行 ID：

```bash
export TESTHUB_EXECUTION_ID='01...'
node services/testhub-cli/src/index.js execution wait \
  --wait-timeout 900 --poll-interval 5

node services/testhub-cli/src/index.js execution report
```

`execution wait` 只输出执行 ID、任务 ID、状态、固定状态码、轮询次数和等待时长；它不输出场景名称、状态消息、请求/响应、变量、密钥或日志。终态为 `SUCCEEDED` 时退出码为 `0`；`FAILED`、`CANCELLED`、`REJECTED` 或服务端 `TIMED_OUT` 为 `2`；CLI 本地等待超时为 `3`；协议或连接问题为 `1`。这样 CI 能在保留脱敏摘要的同时正确阻断流水线。

如需由 CI 归档为下载文件，CLI 会先请求 `/report/integrity`，再下载 `/report/export`，逐一校验 ETag、`X-TestHub-Report-SHA256` 和本地文件 SHA-256；所有检查通过后才会以原子方式写入目标文件：

```bash
node services/testhub-cli/src/index.js execution export \
  --output './artifacts/testhub-execution-report.json'
```

可选的 `--if-none-match '"<sha256>"'` 会把 ETag 发给导出接口；若报告未变化，CLI 返回 `not-modified` 且不会改写已有归档文件。导出响应使用 `private, no-cache`，因此 CI 可以安全地复用缓存文件但必须重新校验。

低层 HTTP 调用仍可用于无法运行 Node.js 的环境。下载前可获取校验元数据；其中 `data.sha256` 与下载响应的 `X-TestHub-Report-SHA256` 相同，`data.eTag` 可作为后续 `If-None-Match` 请求头：

```bash
curl --fail-with-body \
  "$TESTHUB_URL/api/v1/projects/$TESTHUB_PROJECT_ID/api-automation/executions/$TESTHUB_EXECUTION_ID/report/integrity" \
  --header "Authorization: Bearer $TESTHUB_TOKEN"
```

报告不会含 URL、请求/响应正文、头、Cookie、变量取值、环境密钥名称或 Runner 日志文本。报告版本 5 的 `failureStrategy` 只输出冻结策略与固定的安全继续次数；`parallelGroups` 只输出并发分支的固定计数、序号范围和累计耗时，适合 CI 归档和判断，不会包含失败原因或用户内容。

## 签名 Webhook

项目管理员在接口自动化页面创建 Webhook 后，原始密钥只返回一次。服务端仅保存 AES-GCM 密文。入站地址为：

```text
POST /api/v1/integrations/api-automation/webhooks/{webhookId}/trigger
```

请求体只能是 `{}` 或 `{"timeoutSeconds":300}`。请求必须带下列头：

- `X-TestHub-Webhook-Timestamp`：UTC ISO-8601 时间，允许与服务端相差最多 5 分钟。
- `X-TestHub-Webhook-Nonce`：16–128 位 URL 安全随机字符串，仅能使用一次。
- `X-TestHub-Webhook-Signature`：HMAC-SHA256 十六进制签名。

签名原文按 UTF-8 精确拼接：`timestamp + "\n" + nonce + "\n" + 原始请求体`。不要重新格式化 JSON。

```bash
export TESTHUB_WEBHOOK_URL='https://testhub.example.com/api/v1/integrations/api-automation/webhooks/01.../trigger'
export TESTHUB_WEBHOOK_SECRET='thw_...'

BODY='{"timeoutSeconds":300}'
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
NONCE=$(openssl rand -hex 16)
SIGNATURE=$(printf "%s\n%s\n%s" "$TIMESTAMP" "$NONCE" "$BODY" | openssl dgst -sha256 -hmac "$TESTHUB_WEBHOOK_SECRET" -hex | sed 's/^.* //')

curl --fail-with-body --request POST "$TESTHUB_WEBHOOK_URL" \
  --header 'Content-Type: application/json' \
  --header "X-TestHub-Webhook-Timestamp: $TIMESTAMP" \
  --header "X-TestHub-Webhook-Nonce: $NONCE" \
  --header "X-TestHub-Webhook-Signature: $SIGNATURE" \
  --data "$BODY"
```

Webhook 被停用或撤销后不能再触发执行；重复 nonce、过期时间戳和错误签名都会被拒绝。

## CI 示例

GitHub Actions：

```yaml
- name: Run TestHub API automation gate
  env:
    TESTHUB_TOKEN: ${{ secrets.TESTHUB_TOKEN }}
  run: |
    node services/testhub-cli/src/index.js execution run \
      --timeout 300 --wait-timeout 900 --poll-interval 5 \
      --report-output './artifacts/testhub-execution-report.json'
```

Jenkins Pipeline：

```groovy
withCredentials([string(credentialsId: 'testhub-api-token', variable: 'TESTHUB_TOKEN')]) {
  sh '''node services/testhub-cli/src/index.js execution run \
    --timeout 300 --wait-timeout 900 --poll-interval 5 \
    --report-output ./artifacts/testhub-execution-report.json'''
}
```

GitLab CI：

仓库提供可复制的 [GitLab CI 模板](../ci/gitlab/testhub-api-automation.yml)。它使用 Node.js 22 运行一键 `execution run`，无论任务成功或失败都会归档已完成的脱敏报告。将模板复制到 `.gitlab-ci.yml`，或以 `include:local` 的方式引入；如果现有流水线没有 `test` 阶段，请先调整模板中的 `stage`。

在项目的 **Settings / CI/CD / Variables** 中创建并标记为 protected + masked 的变量：`TESTHUB_URL`、`TESTHUB_PROJECT_ID`、`TESTHUB_SCENARIO_ID`、`TESTHUB_TOKEN`。可按需覆盖非敏感变量 `TESTHUB_EXECUTION_TIMEOUT`、`TESTHUB_WAIT_TIMEOUT` 和 `TESTHUB_POLL_INTERVAL`。模板不会打印或写入令牌。
