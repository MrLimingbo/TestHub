# TestHub 缺陷清单

测试日期：2026-10-26。以下问题均有本轮执行证据。严重级别为测试建议，不代表已完成修复；本轮未修改产品代码。

## BUG-001：非执行 Runner 接收取消命令后确认取消，实际执行节点未感知

- **建议级别：高 / P1**
- 关联用例：`QA-0384`。
- 模块：API Runner / 分布式执行取消。
- 验证层级：**双实例内存客户端探针 + 当前源码检查**；未连接真实 Redis，不能表述为已完成真实双节点集群端到端复现。
- 影响：若共享取消消费组将命令投递给非执行节点，该节点会发布 `CANCELLED` 并 `XACK`，任务所有者的本地取消状态仍为 false；存在界面显示取消、实际执行继续的风险。

### 前置条件

1. 保留本轮快照 `.testhub/qa-full-20261026-1436/snapshot/`。
2. 可运行项目既有 Node.js 环境，不需要启动数据库或 Redis。

### 详细复现步骤

1. 在项目根目录运行：
   ```bash
   node .testhub/qa-full-20261026-1436/run-probe.mjs
   ```
2. 探针构造独立的 `ApiRunner` 实例 A（`qa-owner-a`）和 B（`qa-other-b`），各自持有独立取消状态。
3. 构造相同 `taskId`、`executionId` 的合法取消命令，将它交给 B 的 `handleCancellationEntry()`，模拟共享消费组把取消消息投递到非任务所有者。
4. 记录 B 发布的命令及 A、B 对该任务的 `isCancelled()` 结果。
5. 对照快照 `services/api-runner/src/runner.js:142–149`：各节点通过同一个 `CANCEL_CONSUMER_GROUP` 读取取消流；再对照 `531–547` 与 `574–590`：处理函数只更新本实例 Map，随后立即发布取消并确认消息。

### 预期结果

非任务所有者不能仅凭收到命令就将任务当作真正取消完成；取消必须最终到达任务所有者，实际执行停止后再上报确认，或具备等效的共享取消状态/可靠路由机制。

### 实际结果

```json
{
  "ownerObservedCancellation": false,
  "otherObservedCancellation": true,
  "otherAcknowledged": true,
  "otherPublishedCancelled": true
}
```

### 证据与建议

- `.testhub/qa-full-20261026-1436/evidence/QA-0384-probe.json`
- `.testhub/qa-full-20261026-1436/probe-results.json`
- 建议：按任务所有者路由取消，或采用任务所有者可读取的持久化取消状态；区分“取消请求已受理”和“执行已终止”，增加真实 Redis 双 Runner 集成回归。
- **剩余验证边界**：探针未让 A 实际执行一个长耗时 HTTP 任务，因此“外部副作用继续发生”是风险判断，不是本轮已观察到的外部业务事实。

---

## BUG-002：不支持的 Content-Type 被映射为 HTTP 500

- **建议级别：中 / P2**
- 关联用例：`QA-0272`，首轮和复测一致。
- 模块：公共 HTTP 异常处理。
- 环境：专用 desktop/H2；API `http://127.0.0.1:18086`；快照源码构建。

### 前置条件

1. 使用本轮保留数据库启动专用服务，或在相同源码的新隔离环境中准备一个有写权限的项目。
2. 使用有项目权限的账号登录，保留会话 Cookie。
3. 调用 `GET /api/v1/auth/csrf`，获取 `data.headerName`、`data.token`，在后续写请求中携带该动态 CSRF 头及 Cookie。
4. 本轮项目 ID 可在 `setup-context.json` 或 `fixture-ids.json` 查到。

### 详细复现步骤

1. 请求路径：`POST /api/v1/projects/{projectId}/functional/catalogs`。
2. 设置请求头：`Accept: application/json`、`Content-Type: text/plain`，以及步骤中获取的有效 CSRF 头。
3. 请求体发送原始文本：`plain-text`，不要 JSON 序列化。
4. 查看 HTTP 状态码及返回正文。

示意请求（`<...>` 必须替换为实际会话和动态值）：

```http
POST /api/v1/projects/<projectId>/functional/catalogs
Cookie: <当前会话Cookie>
<csrf.headerName>: <csrf.token>
Accept: application/json
Content-Type: text/plain

plain-text
```

### 预期结果

返回 HTTP **415 Unsupported Media Type**，使用统一错误格式说明媒体类型不受支持，而不是将客户端格式错误当作服务内部故障。

### 实际结果

返回 HTTP **500**：

```json
{
  "code": "INTERNAL_ERROR",
  "message": "服务暂时不可用",
  "requestId": "01M5VG7V7P22EMGB6R7FCZH6HP",
  "details": {}
}
```

### 证据与建议

- 首轮：`evidence/QA-0272.json`。
- 复测：`evidence/retest-QA-0272.json`。
- 当前 `ApiExceptionHandler.java:86–88` 的通用异常分支固定返回 500，且未定义不支持媒体类型的专门处理；这是源码定位线索，不是已采集的异常堆栈证明。
- 建议为 `HttpMediaTypeNotSupportedException` 显式返回 415，并加入真实 HTTP 回归。

---

## BUG-003：不存在的已认证 API 路径返回 HTTP 500 而非 404

- **建议级别：中 / P2**
- 关联用例：`QA-0438`。
- 模块：路由/公共 HTTP 异常处理。
- 环境：与 BUG-002 相同。

### 前置条件

使用有效会话登录本轮服务。本用例使用已认证用户，避免未登录安全拦截的 401 掩盖路由行为。

### 详细复现步骤

1. 使用已登录会话发起：
   ```http
   GET /api/v1/qa-definitely-missing-20261026
   Cookie: <当前会话Cookie>
   Accept: application/json
   ```
2. 路径在当前项目中不存在，不需要请求体，也不需要 CSRF 写操作头。
3. 查看状态码和正文。

### 预期结果

HTTP **404 Not Found**，返回统一的资源不存在错误，不计入内部服务故障。

### 实际结果

HTTP **500**：

```json
{
  "code": "INTERNAL_ERROR",
  "message": "服务暂时不可用",
  "requestId": "01M5VG7TQ1765AAC8SX2M026VC",
  "details": {}
}
```

### 证据与建议

- `evidence/retest-QA-0438.json`。
- 建议区分框架无路由/无静态资源异常和真正内部异常，明确映射为 404；分别验证已认证、未认证访问不存在路径时的安全策略。
- BUG-002 与 BUG-003 很可能属于同一类公共异常映射问题；本报告按独立请求行为登记为两项，可由开发合并到同一个修复任务，但不能因此重复计算失败用例。

---

## 已排除的首轮异常，不计产品 Bug

1. 导出尚无功能资产的项目返回 400：补齐资产后通过。
2. 未授权 `https://example.test` 浏览器目标被拒绝：安全策略正确，改用明确授权的本轮 Web Origin 后通过；另新增未授权目标反例确认拒绝。
3. 快照步骤未全部通过时不能将整条用例标记通过：业务约束正确，补齐步骤后通过；新增反例保留此约束检查。
4. 成员响应字段为 `membershipId`，脚本误取 `id`：测试脚本问题；修正取值后移除成员、立即撤权检查均通过。
5. 初始快照遗漏源文件/根目录脚本：测试准备问题；补全后重新编译与回归通过，首轮保留在 `attempt-1-harness/`。
6. 首轮高并发 Vitest 有一条非稳定失败：限制 4 workers 后整套 543 条通过，未据此认定功能缺陷；原始结果保留。
7. 首轮匿名页面导航等待 `networkidle` 超时：改为等待实际 URL 和登录表单后通过；不将等待策略错误当产品 Bug。
