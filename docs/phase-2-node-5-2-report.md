# 阶段 2 · 节点 2.5.2 验收报告：任务中心基础能力

完成日期：2026-07-12

## 本节点范围

- 按项目查看执行任务列表，支持关键词搜索和任务类型展示。
- 创建任务并进入 `QUEUED` 队列。
- 更新任务名称、状态和进度，支持队列、执行中、成功、失败、取消等基础状态。
- 对排队中或执行中的任务执行取消操作，并记录审计动作。
- 当前仅建设控制面基础能力，不启动真实 Runner、不消费 Redis Streams。

## 权限与安全边界

- 项目成员可读取任务；系统管理员、组织管理员、项目管理员、测试负责人和测试工程师可创建及更新任务。
- 任务数据携带组织和项目作用域；结束状态不可再次取消。
- 控制面只保存任务元数据，不执行用户脚本，后续 Runner 接入时再增加队列、心跳、重试和日志能力。

## 接口

- `GET /api/v1/projects/{projectId}/tasks`
- `POST /api/v1/projects/{projectId}/tasks`
- `PUT /api/v1/projects/{projectId}/tasks/{taskId}`
- `POST /api/v1/projects/{projectId}/tasks/{taskId}/cancel`

## 验证结果

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH ./mvnw -q -pl services/platform-api test`：通过，9 个权限与流程测试、2 个认证测试、1 个应用测试均无失败。
- 真实 HTTP：创建接口任务、查询列表、更新为执行中 45% 并保留为浏览器预览数据。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过；保留既有 Monaco source map 和大分包提示。
- 浏览器：任务中心页面、任务创建表单和 45% 进度展示均已验证；修正了 Arco 进度条比例显示问题，控制台无错误。

## 用户预览

- 任务中心：<http://localhost:5173/tasks>

下一步等待确认：节点 2.5.3 MinIO 文件服务。
