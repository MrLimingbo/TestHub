# 阶段 8 · 节点 2：定时执行、重试、取消与功能测试追溯

## 交付内容

- 新增 `ui_automation_schedule` 与 V44 Flyway 迁移；计划按项目、组织、场景和可选功能测试计划隔离。
- 用 Quartz 依据持久化 Cron 计划触发 Web UI 场景；应用启动时重建已启用的计划。
- 执行记录冻结可选功能测试计划名称、计划来源、父执行和重试次数，支持报告与历史追溯。
- Runner 取消走独立 Redis Stream。队列未投递时立即取消；已投递或运行中时关闭受限浏览器上下文，并等待 Runner 上报最终取消事件。
- 定时计划可设置 0–5 次失败重试和 5–3600 秒退避。仅失败、拒绝和超时触发自动重试；用户取消不会重试。
- 手工执行可关联功能测试计划；失败、拒绝和超时的手工执行支持创建一次新的重试执行。
- Web UI 自动化页面增加“定时执行”页签、计划编辑、立即执行、执行计划关联、取消和重试操作。

## 验证

- `npx pnpm@10.13.1 lint`
- `npx pnpm@10.13.1 test`
- `npx pnpm@10.13.1 build`
- `./mvnw -q -pl services/platform-api -Dtest=UiAutomationFlowTests test`
- `./mvnw -q -pl services/platform-api test`
- `npx pnpm@10.13.1 --dir services/playwright-runner lint`
- `npx pnpm@10.13.1 --dir services/playwright-runner test`
- `docker compose config`

浏览器预览已确认 Web UI 自动化路由正常渲染，场景编排入口可打开；定时执行页签呈现计划、场景、功能测试计划、Cron、重试和状态列。

## 下一节点

阶段 8 的下一节点将扩展并行浏览器执行与更丰富的运行工件/失败定位体验；保持隔离 Runner 与控制面之间的协议边界。
