# 阶段 4.3.2.3：智能示例 Mock 报告

## 交付范围

- Mock 服务新增“智能示例 Mock”开关，默认关闭；开关按 Mock 服务独立保存。
- 仅将当前项目中**已发布接口**的**当前已发布版本**作为候选来源，不读取草稿、历史版本或其他项目的接口资产。
- 运行时支持精确路径和 `{pathParam}` 路径模板匹配，并从接口响应示例生成状态码、响应体与内容类型。
- 智能示例响应带有 `X-TestHub-Mock-Source: api-example` 与 `X-TestHub-Api-Version` 响应头，便于调用方追溯来源。
- 手动规则（包含条件规则）始终优先于智能示例；未命中手动规则时，才使用已启用的智能示例回退。
- `/mock` 管理页新增开关、候选接口数、来源刷新与接口版本 / 响应预览清单。

## 数据与安全边界

- 迁移 `V11__mock_smart_examples.sql` 为 `mock_server` 增加 `smart_example_enabled`，默认 `false`。
- 示例来源按项目范围读取，并要求调用者具备当前项目读取权限。
- 示例仅作为静态响应数据返回，不执行用户脚本、动态表达式或模板替换。
- 未启用智能示例且未命中手动规则时，继续返回既有的 `404 MOCK_RULE_NOT_FOUND`。

## 验证记录

- `./mvnw -q -pl services/platform-api -Dtest=SmartExampleMockFlowTests test`：通过，覆盖已发布接口、路径参数、示例状态码、来源响应头、手动规则优先级和关闭后的 404。
- `./mvnw -q -pl services/platform-api test`：通过。
- `npx pnpm@10.13.1 lint && npx pnpm@10.13.1 test && npx pnpm@10.13.1 build`：通过。
- 本地控制面已启动并完成 V11 迁移校验；开发页面服务运行于 `http://localhost:5173`。
- 浏览器自动化受当前本地 URL 访问策略拦截，未绕过该限制；请按下列步骤在已有浏览器中完成页面验收。

## 用户预览步骤

1. 打开 `http://localhost:5173/mock`，选择已启用的 Mock 服务。
2. 在“智能示例 Mock”区域确认可见已发布接口候选；例如已有的 `查询订单详情`。
3. 启用开关，并调用该服务公开地址下与接口定义相同的路径，例如 `/api/v1/orders/OD-1`。
4. 确认响应来自接口示例，并包含 `X-TestHub-Mock-Source: api-example`；再调用已有手动规则路径，确认手动规则仍优先返回。
