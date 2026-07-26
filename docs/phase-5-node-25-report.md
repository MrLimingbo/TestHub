# 阶段 5 · 节点 25：受限 pm.* 脚本兼容与断言

## 交付内容

- API Runner 的隔离脚本环境新增受限 Postman 风格 `pm.*` 兼容层：支持临时变量、环境别名、状态码读取、`pm.test()` 与基础 `pm.expect()` 断言。
- 允许传统 `function () {}` 形式的 `pm.test` 回调；动态 `Function` 构造器仍在控制面和 Runner 双重禁止。
- 任一 `pm.test()` 失败会终止当前执行并仅上报固定脚本失败事件，不保存测试名称、异常、变量值、请求内容或响应内容。
- 控制面在保存场景时拒绝不在矩阵内的 `pm.*` 能力，例如 `pm.sendRequest`、Cookie、集合/全局变量、响应正文和包加载。
- 接口自动化脚本编辑区显示兼容范围、受限范围与可复制的前置变量/后置状态断言示例。

## 安全边界

- `pm.environment` 仅是当前 Runner 临时变量视图的别名，不能读取或写入项目环境和环境密钥。
- 兼容层在 `node:vm` 内由脚本文本构造；没有主机对象或主机回调进入上下文，代码生成、模块、网络、文件、进程、定时器及异步能力均不可用。
- 当前节点不接入 Postman Runtime 的完整集合 Runner；完整引擎的请求发送、Cookie、文件和包解析能力不属于受限 Runner 权限范围。详细矩阵见 [`api-automation-pm-compatibility.md`](api-automation-pm-compatibility.md)。

## 验证

- API Runner 单测覆盖变量读写、状态断言、断言失败和不支持 API 的拒绝。
- 控制面集成测试覆盖 `pm.test(function () {})` 可保存、`pm.sendRequest` 保存阶段拒绝。
- 前端 lint、单测与生产构建覆盖编辑器展示变更。
