# 接口调试模块缺陷修复与回归（2026-10-24）

QA-DEBUG-001～010修复完成，用户审核待定。

- QA-DEBUG-001：HTTP 与 WebSocket 追加 Query API Key 时保留已有 URI 的 raw path/query，只对新键值编码一次；保留此前移除 fragment 的行为。
- QA-DEBUG-002：WebSocket 在解析变量、解密密钥和握手前校验所选环境属于当前项目且启用。
- QA-DEBUG-003：gRPC 统一在变量解析入口校验项目环境；覆盖在线服务/方法发现、Unary、服务端流、客户端流及双向流，流消息发送时也重新校验。
- QA-DEBUG-004：客户端流及双向流在下一条消息将突破100条或512KiB发送配额时设置LIMIT_REACHED/结束时间、移除订阅并在锁外取消传输；保留超限请求409，记录一次结束审计。
- QA-DEBUG-005：先恢复路由/历史草稿再选择环境；参数及密钥标签从已加载数据按当前环境计算，避免历史或同步模板切换环境后留下旧过滤结果；环境异步加载增加归属和代次检查。
- QA-DEBUG-006：保存接口弹窗采用独立代次，取消/重开/项目用户切换/卸载后旧响应不得关闭新弹窗、写错误、显示成功或结束新spinner；CSRF等待后再次检查归属，提交前序列化固定请求。
- QA-DEBUG-007：服务/方法发现共享最新请求代次；目标、来源、环境、协议、TLS、超时、Metadata、认证、服务或项目变化使旧结果失效；旧成功/错误/finally均不得污染新结果和loading。
- QA-DEBUG-008：WS连接/消息/关闭操作及轮询捕获项目、用户、组件生命周期和会话代次；旧回调不使用新项目路径、不再启动轮询；首次读取失败不会误报连接成功。
- QA-DEBUG-009：历史HTTP Header仍按name/value恢复；gRPC Metadata明确按key/value恢复，移除无效的replaceAll。
- QA-DEBUG-010：正式接口构造URL/Header前，对required Query/Header执行非空（含空白）及启用状态校验，错误指出具体字段；可选空值仍省略。

验证：模块217项，211通过/0失败/6观察；相关后端64项及新增前端19项通过；隔离H2后端全套899项、前端全套508项、lint、构建及diff检查通过。6项观察和原生/多数据库范围限制继续保留。

详细报告：[TEST-REPORT.md](/Users/limingbo/Documents/codeXProject/testHub/output/qa-20261024/module-07-api-debug/regression-r1/TEST-REPORT.md)。
