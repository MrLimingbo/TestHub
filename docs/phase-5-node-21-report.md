# 阶段 5 · 节点 21：隔离前后置 JavaScript

## 交付内容

- 场景新增前置与后置 JavaScript 字段，并通过 Flyway `V24` 持久化；每次场景版本更新都会将脚本源纳入不可变修订快照。
- 控制面只做长度、危险运行时关键字与脚本能力边界校验，不解释、不编译、不执行用户脚本。
- Runner runtime configuration 升级为工件版本 4 和能力类型 `HTTP_FLOW_CONTROL_SCRIPTS_V1`；脚本文本和冻结变量只通过签名绑定、短时租约响应交付给可信 Runner。
- 前置脚本会在每个 HTTP 请求前运行，后置脚本会在每个 HTTP 响应后运行；可读写 `variables`，后置脚本额外可读 `response.status`。
- Runner 使用 JSON 跨隔离边界、禁用宿主能力的同步 VM 上下文，并将脚本 CPU 时间限制在 150ms；脚本结果、变量和异常详情绝不写入日志、报告、Outbox 或 Redis 任务流。
- Compose Runner 启用非 root、只读根文件系统、无 capability、`no-new-privileges`、PID 与内存上限。
- 接口自动化页新增“场景前后置脚本”编辑区和边界说明。

## 验证

- API Runner 单元测试覆盖变量变更、固定日志、进程访问拒绝与死循环超时。
- Spring 集成测试覆盖脚本保存、控制面拒绝危险能力和 v4 runtime configuration 交付。
- 前端生产构建通过。

## 运行边界

当前只支持同步 JavaScript 与 HTTP 状态码元数据，不支持 `pm.*`、响应正文解析、请求头/Cookie、数据库操作、网络 I/O、模块加载、计时器、异步函数或环境密钥交接。后续的 Postman/Apifox 扩展兼容层需要继续保留本节点的 Runner 容器边界和脱敏约束。
