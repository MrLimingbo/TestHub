# 接口自动化模块：12项缺陷修复与R1回归

范围限于QA-AUTO-001—012，完成后等待用户审核，不进入Mock。首轮复现与214项冻结用例保留在`output/qa-20261026/module-08-api-automation/`，本轮结果单独保存在其`regression-r1/`目录。

| Bug | 修复位置 | 行为变化 |
| --- | --- | --- |
| 001 | ApiAutomationScenarioController | List元素使用@NotNull，null步骤在创建/更新映射前返回400，不产生额外修订 |
| 002 | ApiAutomationExecutionService | 仅为实际引用的环境密钥记录withheld名称；未引用密钥不阻断执行，真实引用仍拒绝，密钥不解密、不交付 |
| 003 | api-runner/http-executor.js | 带正文请求发送UTF-8字节数的Content-Length，DELETE/OPTIONS正文保持完整 |
| 004 | api-runner/runner.js | 条件跳过请求保留最近真实响应，后续条件可继续使用其状态；并行组的单响应隔离保持 |
| 005 | AutomationPage | 新建场景绑定弹窗会话与项目，创建加载状态独立；旧响应不清空或关闭新草稿 |
| 006 | AutomationPage | 保存捕获选择代次，在响应/修订回读后核对；失效保存不覆盖B，也不继续运行错误选择 |
| 007 | AutomationPage | 场景详情/修订只接受最新选择代次，旧成功/失败不影响后选场景 |
| 008 | AutomationPage | 数据集文本/文件创建绑定会话，仅当前操作可关闭弹窗和打开预览 |
| 009 | AutomationPage | 配置创建/更新捕获编辑ID与会话；旧请求不能关闭新草稿或清掉新请求加载状态 |
| 010 | AutomationPage | 计划创建/更新绑定会话与编辑ID，旧响应不关闭新计划 |
| 011 | AutomationPage | 项目代次和卸载标记使旧列表失效，阻止错误项目路径的详情读取；卸载后不重启初始加载的轮询 |
| 012 | AutomationPage | 输入、弹窗或场景变化使预览代次失效；旧响应不能重新显示旧来源或结束新请求加载状态 |

四类弹窗与预览在取得CSRF后、真正提交前再核对当前上下文；当前操作的错误仍显示并可重试，过期错误不干扰新操作。此次不增加数据库迁移、依赖或公共API字段，不更改密钥交付、网络白名单、权限和版本边界。

新增测试：后端2项（创建/更新null步骤、未引用密钥快照），Runner6项（五种正文方法UTF-8字节长度、跳过后连续条件），页面35项（迟到成功/失败、替换草稿、新请求加载状态、CSRF期间取消/切项目、当前失败重试、选择/保存/列表、卸载与预览失效）。原214项功能验收与12个Bug新实例复验不合并计数。

本轮使用仓库规定的隔离后端启动器；所有浏览器检查均headless、后台运行。最终统计、执行证据、观察与清理结果以`output/qa-20261026/module-08-api-automation/regression-r1/TEST-REPORT.md`为准。
