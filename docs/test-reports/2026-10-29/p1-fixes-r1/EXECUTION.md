# 执行、证据与复跑

所有后端运行均使用JDK21及新隔离数据，不对默认数据源执行完整Maven测试。当前宿主使用仓库`.testhub/desktop-runtime/darwin-arm64/jre`中的原生JDK21工具。下列`<JDK21>`须换为实际绝对路径；不提供或保存QA凭据。

```sh
# 初始逐项复现/回归，选择器可替换为对应P1类；自有H2目录自动清理
JAVA_HOME=<JDK21> node scripts/qa/test-p1-backend.mjs 'P1*Tests'

# H2完整套件与JAR构建
JAVA_HOME=<JDK21> node scripts/desktop/test-local-backend.mjs

# 最后D25补修后H2专项及打包（88条，86通过，2条MySQL专属跳过）
JAVA_HOME=<JDK21> node scripts/qa/test-p1-backend.mjs 'P1*Tests,NotificationOperationsFixesFlowTests' package

# 最后完整重跑：全新私有MySQL/Redis/S3，结束清理其容器、卷及目录
JAVA_HOME=<JDK21> node scripts/desktop/test-mysql-backend.mjs

# 前端：保留原5秒超时，降低并发复验首轮资源竞争失败
npx pnpm@10.13.1 lint
npx pnpm@10.13.1 test --maxWorkers=2
npx pnpm@10.13.1 build

# 无窗口Chrome（HTTP夹具）及真实JAR HTTP（新工作区、自动清理）
node scripts/qa/p1-observation-ui.mjs
JAVA_HOME=<JDK21> node scripts/qa/p1-desktop-http.mjs
git diff --check
```

最新MySQL全量于2026-10-29 18:15:02 +08:00结束，1,035条全部通过。测试源码在这次执行启动后未改动。最新JAR的SHA-256记录在summary.json，真实JAR HTTP结果来自最后专项打包后的该产物；没有安装或替换用户现有运行时。

|文件|内容|
|---|---|
|CASE-RESULTS.json / .csv|72条正式用例，带正反向、状态、定位和证据来源。|
|BACKEND-MYSQL-RESULTS.json|最新1,035条完整后端逐例结果。|
|BACKEND-MYSQL-FIRST-RESULTS.json|首轮1,025条，保留Redis 500与工件归档502的两处失败。|
|BACKEND-H2-RESULTS.json|D25最后补修前完整H2结果，1,025条、3跳过。|
|BACKEND-H2-FINAL-FOCUSED.json|最后补修后88条专项与存储探针回归。|
|BACKEND-H2-FIRST-RESULTS.json|首轮旧资产与新规则冲突的7条失败/错误。|
|FRONTEND-RESULTS.json|最终826条前端结果，含原批56条保护用例。|
|FRONTEND-FIRST-SUMMARY.json|首轮20条失败摘要，原超时未修改。|
|BEFORE-RESULTS.json|先行复现各规则结果；D11原契约本来通过。|
|BROWSER-RESULTS.json|7条无窗口浏览器场景；明确HTTP夹具，不替代真实后端。|
|DESKTOP-HTTP-RESULTS.json|最新JAR的7条真实HTTP/重启/启动回收检查；明确不属于Electron升级测试。|
|SOURCE-CHANGES.json|本轮源码指纹及与开始时基线的变化范围。|
|SOURCE-INDEX.json|审核包文件SHA-256索引。|

原始日志、JUnit properties和临时数据可能包含隔离会话或环境信息，因此不作为交付材料；保留脱敏逐例结果、首轮失败类别及清理记录。重复执行会重新生成新的QA数据和执行结果。
