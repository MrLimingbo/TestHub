# 阶段 9 · 节点 1：移动端资产、声明式场景与隔离 Runner 传输基础

## 交付内容

- 新增项目隔离的移动设备、应用包、元素对象、场景、执行、执行清单、步骤结果与 Runner Outbox 数据模型；所有业务记录携带组织和项目范围。
- 提供 `/api/v1/projects/{projectId}/mobile-automation` API：登记或删除设备、应用和元素，创建或删除声明式场景，提交执行任务，并查看步骤级报告。
- 场景支持启动、点击、输入、可见/文本断言、等待和截图步骤；每一步都有严格的时限、元素引用、平台一致性和变量边界校验。
- 执行时冻结设备、应用、变量、定位器与步骤到不可变 JSON 清单，以事务 Outbox 投递至独立 `testhub:runner:appium:*` Redis Streams；控制面不执行用户脚本或设备操作。
- 新增独立 Node.js Appium Runner 和 Docker Compose 的 `runner` profile。容器为只读根文件系统、无 Linux capability、受进程/内存/临时目录限制，并默认关闭设备执行。
- Runner 在未显式配置设备农场与驱动适配器时只接受任务并返回可解释的拒绝状态，不会尝试访问任何真机、模拟器或外部网络。
- 新增“移动端自动化”可操作页面：可登记 Android/iOS 设备、应用与元素，编排场景、提交执行，并浏览状态及步骤报告；页面明确标识当前安全默认值。
- 修复了场景步骤将毫秒超时误按执行秒数校验的问题，并补充 Redis 消费组已存在时的幂等初始化处理。

## 验证

- `./mvnw -q -pl services/platform-api -Dtest=MobileAutomationFlowTests test`：通过；覆盖项目范围资产、场景、冻结清单、Runner 事件、步骤结果与报告。
- `./mvnw -q -pl services/platform-api test`：全套通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过；Vitest 仅输出已有 Monaco sourcemap 缺失警告。
- `npx pnpm@10.13.1 --dir services/appium-runner lint && npx pnpm@10.13.1 --dir services/appium-runner test`：4 项通过，覆盖任务协议、容量边界、事件和心跳。
- `docker compose config` 与 `git diff --check`：通过。

## 后续节点边界

本节点完成的是安全可审计的移动端资产与任务传输闭环。下一节点才会在明确的设备农场地址白名单与凭据配置下接入 W3C Appium 驱动、截图工件、取消/超时恢复及真实设备执行；在那之前，默认拒绝实际设备操作。
