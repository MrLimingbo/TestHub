# 接口测试模块回归记录（2026-09-08）

状态：本节点完成，暂停。下次用户说“继续执行”时，从“接口自动化”模块开始。功能测试模块已在上一节点完成，本次保留其未提交改动。

## 结果与范围

- 前端：12 个测试文件，63 项通过，0 失败。
- 后端：接口设计、导入导出、HTTP 调试与目标策略、环境、WebSocket、gRPC 相关 9 个测试类，41 项通过，0 失败。
- 页面检查：以下 22 项，正向 13 项、反向 9 项；修复后 22/22 通过。正反向按预期行为分类，拒绝非法请求属于通过。
- 发现并修复 5 类缺陷；变量和 URL 片段问题先用新增测试复现失败，再修复。其余问题通过实际页面重现及回归。
- ESLint、vue-tsc、Vite 构建、git diff --check 通过。

## 缺陷与修复

| 编号 | 问题与复现 | 修复与回归证据 |
| --- | --- | --- |
| API-R2-001 | 查询参数填写 `{{$timestamp}}` 后发送，Echo 收到模板原文；路径参数和 URL 编码表单存在相同提前编码问题 | 编码时保留执行期模板，普通文字仍编码；接口调试和快捷请求真实 Echo 返回数值时间戳，缺失变量返回 400；新增路径、查询及表单测试 |
| API-R2-002 | URL 为 `/get#section?example=1` 时，片段中的文字被当成查询参数，重新同步会向请求追加参数 | 先判断片段位置；新增测试验证解析结果为空且 URL 保持原样 |
| API-R2-003 | 保存包含 `qa_marker=QA-R2-表单回放` 的 form-data 草稿，重新打开后表单字段清空 | 恢复文本 multipart 字段、重复字段及换行；非支持格式保留原始正文；同步发送 boundary。回放后真实 POST 返回完整中文字段，新增往返及不完整输入测试 |
| API-R2-004 | 超时设置 5000 ms，保存再打开恢复为 10000 ms，摘要固定显示 10 秒 | 恢复保存的有效超时，摘要使用实际值；页面重新打开显示 5000 ms / 5 秒 |
| API-R2-005 | 成功导入后再次导入相同文件，冲突报错但仍显示上一次“导入完成” | 每次导入开始清除旧结果，包括本地校验失败和 cURL 分支；连续成功、失败回归后仅显示本次冲突，接口总数保持 3 |

## 页面检查清单

| 项目 | 类型 | 最终结果 |
| --- | --- | --- |
| 目录名称为空的校验 | 反向 | 通过 |
| 创建 QA-R2-接口回归目录 | 正向 | 通过 |
| 接口必填项为空的校验 | 反向 | 通过 |
| 创建接口和 1.0.0 初始版本 | 正向 | 通过 |
| 接口调试动态查询参数真实发送 | 正向 | 修复后通过 |
| 快捷请求动态查询参数真实发送 | 正向 | 修复后通过 |
| form-data 保存、重新载入、真实 POST | 正向 | 修复后通过 |
| 5000 ms 超时保存和回放 | 正向 | 修复后通过 |
| 用例名称为空的校验 | 反向 | 通过 |
| 保存为接口测试用例 | 正向 | 通过 |
| 重新运行保存用例，状态码和响应类型校验 | 正向 | 通过 |
| 发布当前接口版本 | 正向 | 通过 |
| OpenAPI 导出并解析文件 | 正向 | 通过 |
| Postman 导出并解析文件 | 正向 | 通过 |
| HAR 导出并解析文件 | 正向 | 通过 |
| 空文档导入 | 反向 | 通过 |
| 无效 OpenAPI 文档导入 | 反向 | 通过 |
| 上传 OpenAPI 文件并导入新接口 | 正向 | 通过 |
| 重复导入冲突校验 | 反向 | 通过 |
| 未定义变量请求 | 反向 | 通过 |
| 回环目标地址请求 | 反向 | 通过 |
| 导入失败后清除上次成功结果 | 反向 | 修复后通过 |

## 验证命令与边界

使用 JDK 21 执行：

```sh
./mvnw -pl services/platform-api -Dtest=ApiDesignFlowTests,ApiInterchangeFlowTests,ApiDebugFlowTests,ApiDebugTargetPolicyTests,JdkApiDebugTransportTests,WebSocketDebugFlowTests,GrpcDebugFlowTests,GrpcReflectionDebugTransportTests,ProjectEnvironmentFlowTests test
./node_modules/.bin/eslint .
./node_modules/.bin/vitest run
./node_modules/.bin/vue-tsc --noEmit
./node_modules/.bin/vite build
git diff --check
```

本次真实网络端到端覆盖 HTTP GET/POST。GraphQL、SOAP、WebSocket、gRPC 依赖现有后端测试及可控传输替身，未宣称完成这些协议的外部服务或桌面客户端端到端验证。构建保留既有大包提示，快捷请求页存在 Vue toRefs 警告，未观察到对应功能失败；没有修改第三方依赖。

手工数据保留：QA-R2-接口回归目录、QA-R2-Echo查询（已发布）、QA-R2-动态参数测试用例、两条 OpenAPI 导入接口和快捷请求草稿。测试进程在暂停前停止；代码未提交、未推送。

本次页面快照和导出文件保留在本地忽略目录 `.testhub/qa-r2-20260908/playwright/`，不纳入代码提交。
