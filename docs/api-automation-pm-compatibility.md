# 接口自动化 pm.* 兼容矩阵 v1

TestHub 的 API Runner 采用最小兼容层支持常见的 Postman 风格脚本。该层只在隔离 Runner 容器中运行；控制面只做静态边界校验，绝不执行用户脚本。

Postman Runtime 是 Apache-2.0 的低层集合执行库。TestHub 不将其完整 Runner 嵌入执行容器：完整集合运行会引入请求发送、Cookie、文件、包解析和请求重放等能力，超出当前受限 Runner 的最小权限模型。本矩阵用于固定当前可移植子集，并为后续兼容适配器扩展提供验收基线。

## 已支持

| Postman 风格 API | TestHub 行为 | 边界 |
| --- | --- | --- |
| `pm.variables.get/has/set/unset/toObject/replaceIn` | 读写当前任务的临时变量 | 不持久化、不进入日志或报告 |
| `pm.environment.get/has/set/unset/toObject/replaceIn` | 当前版本与 `pm.variables` 指向同一受限临时变量视图 | 不会修改项目环境或环境密钥 |
| `pm.response.code` | 返回当前 HTTP 响应状态码 | 仅后置脚本有实际状态值 |
| `pm.response.to.have.status(code)` | 对状态码做精确断言 | 不暴露响应正文、头或 Cookie |
| `pm.expect(value).to.equal/eql` | 严格相等断言 | 支持 `be.true/false/null/undefined/oneOf` 与 `have.status` |
| `pm.test(name, callback)` | 同步执行测试回调 | 任一失败会使当前执行失败；名称和异常详情不持久化 |

## 明确不支持

- `pm.sendRequest`、`pm.execution.*`、`pm.request.*`：不允许脚本发起请求、跳转或重放请求。
- `pm.response.json/text/headers/cookies`：响应内容不传入脚本运行时。
- `pm.cookies`、`pm.globals`、`pm.collectionVariables`、`pm.visualizer`、`pm.vault`、`pm.require`、`postman.*`：不提供跨任务状态、密钥、包加载或可视化能力。
- 网络、文件、进程、模块、动态函数构造器、定时器和异步 API：保存阶段即拒绝，运行期仍由 `node:vm` 禁用代码生成并设置 150ms CPU 限制。

## 兼容性验收用例

```javascript
pm.environment.set('orderId', pm.variables.get('orderId') + '-retry');

pm.test('创建成功', function () {
  pm.response.to.have.status(201);
  pm.expect(pm.response.code).to.eql(201);
});
```

上述脚本可被保存，并只在 Runner 执行期间生效。若状态码不是 `201`，任务失败，平台只记录固定的脚本失败事件。
