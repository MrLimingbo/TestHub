# 阶段 2 · 节点 2.6.1 验收报告：基础平台验收与收尾

完成日期：2026-07-12

## 本节点范围

- 为阶段 2 增加统一 API 错误结构回归测试。
- 增加跨组织项目访问拒绝测试，验证租户边界不会因直接传入项目 ID 被绕过。
- 增加文件上传、下载、删除、MinIO 字节一致性及文件上传审计回归测试。
- 更新本地开发文档，补充控制面集成验收命令和阶段 2 已交付能力清单。
- 阶段 3 功能测试资产、真实 Runner、定时调度和 CI 集成仍未开始。

## 验收覆盖

- 登录、Redis 会话、退出和未登录请求。
- 系统/组织/项目权限与项目级写权限拒绝。
- 项目参数、环境密钥脱敏、系统配置和审计追踪。
- 通知模板和任务中心基础 CRUD/状态流转。
- MinIO 文件对象生命周期与 MySQL 元数据分离。
- Docker Compose 初始化配置与 MySQL 8.4、Redis、MinIO 服务校验。

## 验证结果

- Java 21 Maven 全量测试通过：15 个测试，0 failures、0 errors、0 skipped。
  - `AuthenticationFlowTests`：2
  - `AccessControlFlowTests`：9
  - `PlatformFoundationAcceptanceTests`：3
  - `TestHubApplicationTests`：1
- `npx --yes pnpm@10.13.1 lint`：通过。
- `npx --yes pnpm@10.13.1 test`：通过，2 个测试文件、3 个测试通过。
- `npx --yes pnpm@10.13.1 build`：通过。
- `docker compose config --quiet`：通过。
- `git diff --check`：通过。
- 浏览器已打开任务中心并确认真实任务数据、进度和操作按钮可见；控制台错误为 `[]`。

已知非阻塞提示：Monaco 缺少上游 source map，Vite 报告 Monaco/图表分包较大；Flyway 提示 MySQL 8.4 高于当前已测试版本上限。这些提示不影响本地验收，生产发布前需单独处理依赖和迁移工具版本策略。

## 用户预览

- 任务中心：<http://localhost:5173/tasks>

阶段 2 基础平台节点已完成，下一步等待确认后进入阶段 3 功能测试完整闭环。
