# 阶段 4.1：接口资产设计、版本与文档

验收日期：2026-07-13

## 交付内容

- 将原 Mock“接口设计”页面替换为真实接口资产工作台。
- 新增接口目录、HTTP 接口定义和版本快照；接口定义保存当前版本指针，版本保存独立协议快照。
- 支持 GET、POST、PUT、PATCH、DELETE、HEAD、OPTIONS；接口路径必须以 `/` 开头。
- 支持请求头、查询参数、路径参数、请求体、响应示例、摘要与接口说明。
- 支持基于当前快照创建新版本并发布；发布后更新当前版本并显示在线文档预览。
- 目录、接口、版本和发布动作均具备项目范围校验与审计记录。

## 数据设计

- Flyway `V6__api_design_foundation.sql` 创建 `api_catalog`、`api_definition`、`api_definition_version`。
- 接口路径上限设为 700 字符，确保 MySQL 8 utf8mb4 下的“项目 + 方法 + 路径”唯一索引不超过 3072 字节。
- 协议参数和示例采用 JSON 列存储，为下一节点 OpenAPI/Postman/cURL/HAR 兼容转换提供稳定的中间模型。

## 验证结果

- `./mvnw -q -pl services/platform-api -Dtest=ApiDesignFlowTests test`：通过，覆盖目录、接口初始版本、新版本与发布流程。
- `./mvnw -q -pl services/platform-api test`：通过，20 个后端测试全部成功。
- `npx pnpm@10.13.1 lint`、`npx pnpm@10.13.1 test`、`npx pnpm@10.13.1 build`：通过；前端测试 3 项全部成功。
- `git diff --check`：通过。

## 浏览器验收路径

1. 登录后访问 `/api-design`。
2. 先新建“订单服务”等接口目录。
3. 新建一个接口，填写路径、初始版本与参数，保存后确认接口列表和在线文档出现。
4. 基于当前版本创建 `1.1.0` 快照，切换版本确认文档快照存在。
5. 发布该版本，确认接口状态变为“已发布”、文档标记已更新。

下一节点为 4.2：OpenAPI、Swagger、Postman、cURL 与 HAR 的导入导出兼容能力。
