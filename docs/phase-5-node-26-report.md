# 阶段 5 · 节点 26：pm.test 脚本断言统计

## 交付内容

- 隔离脚本执行器为每次脚本返回变量映射和固定断言统计：总数、通过数、失败数。
- API Runner 使用新增 Redis Stream `testhub:runner:api:script-results:v1` 投递 `PRE_REQUEST`、`POST_RESPONSE` 的统计记录；失败断言会先投递统计，再以既有固定失败日志结束执行。
- 控制面严格校验结果字段、阶段、计数边界和总和，只允许已在冻结清单中配置的脚本阶段写入 MySQL。
- 新增项目读取接口 `GET /api/v1/projects/{projectId}/api-automation/executions/{executionId}/script-results`；执行报告升级为 v2，并增加脚本执行次数和断言总计。
- 执行详情抽屉增加“脚本断言统计”面板与报告卡片，只显示阶段和聚合计数。

## 安全边界

- 不持久化或传输断言名称、表达式、异常、脚本文本、变量名/取值、URL、请求/响应、Cookie、请求头或密钥。
- 每个脚本最多产生 100 条 `pm.test` 统计；协议、Runner 和数据库约束共同校验计数。
- 控制面不运行用户脚本；脚本仍只在受限 Runner 容器内的短时 Node `vm` 上下文执行。

## 验证

- API Runner 单元测试覆盖统计产生、失败先投递、协议字段与脱敏边界。
- 控制面集成测试覆盖 Stream 消费、非法额外字段拒绝、接口结果和报告汇总。
- 前端 lint、Vitest、类型检查和生产构建通过。
