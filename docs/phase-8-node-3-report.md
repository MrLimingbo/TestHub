# 阶段 8 · 节点 3：受控并行执行与失败现场定位

## 交付内容

- Playwright Runner 从固定单槽位扩展为每个实例最多两个浏览器任务；任务在获取 Redis Stream 消息后立即预留槽位，并在完成、失败或取消后释放，心跳同步上报实际活跃数。
- Runner 与控制面的心跳协议均将并发上限限制为 `1–2`，避免伪造或异常心跳宣称不可控的容量。
- Compose 中的 Runner 默认双槽位，使用 `TESTHUB_UI_AUTOMATION_RUNNER_MAX_CONCURRENT_TASKS` 覆盖；容器保持只读、无额外 Linux 能力，并设置 `256` 进程、`1536m` 内存与 `512m` 临时文件上限。
- `TESTHUB_UI_AUTOMATION_RUNNER_EXECUTION_ENABLED` 允许部署环境显式启用执行，默认仍为 `false`；启用后仍必须同时注入控制面地址和目标 Origin 白名单。
- 新增受签名认证保护的 Playwright 内部路径放行；HTTP 层只放行给 Runner 的内部请求，实际上传仍须通过可信心跳、HMAC、60 秒时间窗口和一次性 nonce 校验。
- 执行报告抽屉增加失败步骤定位卡片，直接显示失败步骤序号、名称、类型与错误摘要；截图在线预览、视频播放、Trace 下载与 Trace Viewer 提示均按工件类型呈现。
- 新增在线工件查看接口，只允许项目有读权限的用户以内联方式查看 PNG 截图或 WebM 视频；Trace 等非安全预览格式仅可下载。

## 验证

- `npx pnpm@10.13.1 --dir services/playwright-runner lint && npx pnpm@10.13.1 --dir services/playwright-runner test`：6 项通过，覆盖双槽位预留与释放。
- `./mvnw -q -pl services/platform-api -Dtest=UiAutomationFlowTests test`：通过；覆盖可信 Runner 工件归档、截图内联查看与 Trace 拒绝内联查看。
- `./mvnw -q -pl services/platform-api test`：全套通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；Vitest 仅输出已有 Monaco sourcemap 缺失警告，不影响测试结果。
- `docker compose config`：通过。
- 浏览器预览：`/ui-automation` 路由、Runner 隔离提示、浏览器项目/页面对象/定时计划空态及“新建场景”声明式编排弹窗可正常显示。

## 下一节点

阶段 8 的 Web UI 自动化闭环已完成。待验收通过后进入阶段 9：移动端自动化、插件化与扩展能力。
