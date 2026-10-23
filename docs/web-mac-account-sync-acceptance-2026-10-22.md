# Web 注册、Mac 登录与双向同步验收（2026-10-22）

## 范围与环境

针对正在运行的 Web 服务及本机已安装的 Mac 客户端进行真实联调：

- Web：`http://127.0.0.1:5173`，与 `http://localhost:5173` 使用同一后端，注册功能已开启。
- Web 源码：`20a620194fc93d708b94f9898285ae5a40d4fca9`。
- Mac：`/Applications/TestHub.app`，版本 `0.2.0`；直接运行已安装的打包程序。
- Web 使用无头 Chromium；Mac 使用现有离屏启动器，在应用入口执行前设置独立 QA 用户目录、会话目录和钥匙串名称，禁止窗口显示及抢占焦点。
- 在真实 Web 服务中新注册随机测试账号，只写入其自动创建的私人组织与默认项目。Mac 本地资料全部位于临时工作区。

本轮覆盖项目普通参数、接口设计资料、功能用例、项目文件四类代表性资料的持续双向同步。其他同步模块及各测试执行引擎未在本轮重新全量回归。

## 验收结果

注册、登录、同步的完整流程共 15 项检查通过；另发现下述启动稳定性异常，因此本轮不能视为客户端启动稳定性全部验收通过。

| 检查 | 结果 |
| --- | --- |
| Web 页面注册、自动进入工作区、账号与私人项目归属 | 通过 |
| 已安装 Mac 客户端使用新账号登录，读取同一账号及项目 | 通过 |
| 四类资料分别预览、确认两端项目绑定并首次往返复制 | 各模块通过 |
| 普通参数值、接口与用例数量、文件原始字节核对 | 通过；两端密钥不复制 |
| 两端分别修改参数、接口名称和用例正文，以及新增文件 | 通过；等待真实 30 秒轮询同步，不手动触发 |
| 重复同步 | 通过；接口、用例、文件无重复副本 |
| 同一参数两端并发修改 | 保留冲突，确认选定版本后才更新对端 |
| 完全退出 Mac 后的会话保护 | 会话 Cookie 失效；未登录时自动同步停止，本地资料保留 |
| Mac 重启、原账号重新登录 | 本地工作区及四项项目绑定保留，自动同步恢复 |
| Mac 使用注册邮箱登录、退出会话失效 | 通过 |
| Web 与 Mac 功能用例页面展示对端同步的内容 | 通过 |

**现有登录策略是会话登录。** Mac 客户端完全退出后需要重新登录；已有绑定和本地资料会保留。同一应用运行期间切换本地工作区与账号服务可以复用会话。本轮没有增加“记住登录”功能，也没有延长会话期限。

新账号的网页项目与本地项目需要先逐项确认绑定，随后按各模块规则持续同步。文件内容按新文件复制；项目文件已有的删除暂停策略保持不变。

## 待处理的启动异常

多轮验证中，有一次新建临时工作区启动未进入本地页面，离屏启动器最终报 `Offscreen page unavailable: http://127.0.0.1:`。该次后台日志显示 Spring Boot 已启动，但 API Runner 能力发现返回 HTTP 401，执行组件随后停止。后续完整同步流程可以通过。

该异常的根因尚未确认，本轮未修改业务实现或替换用户安装程序。后续应独立复现本地 Runner 初始化与签名握手，修复后补做多次冷启动验证；不能仅用后续成功运行关闭这个问题。脱敏观察记录为 `.testhub/web-mac-account-startup-observation-20261022.json`。

2026-10-23 更新：已确定并复现心跳清理竞态，修复候选包完成 12 次首启、6 次重启及账号同步复验，详见[修复与验收记录](desktop-runner-handshake-fix-2026-10-23.md)。上段保留初次发现时的状态；本机既有安装尚未自动替换，待本轮审核。

## 数据保留与清理

清理按文件、会话、账号数据的依赖顺序进行：

1. 停止自有 Mac 测试进程，关闭无头浏览器。
2. 使用测试账号的文件 API 删除其 Web 项目文件，删除实际 MinIO 对象。
3. 清理当前及客户端退出前遗留的测试会话：按随机账号精确识别 Redis 主体，再验证 HTTP 身份，通过该账号的退出接口逐一失效。
4. 核对私人组织、项目、账号与成员归属，在保留外键约束的事务中清理专属记录及关联版本、同步回执、审计记录。
5. 删除临时本地数据库、浏览器配置、文件、原始日志、恢复清单及独立 QA 钥匙串。

清理后核对：原有 MySQL 全表校验摘要、Redis 会话主体与配置、MinIO 文件库存、真实 Mac 用户目录和安装程序摘要均保持不变，已安装应用的 `codesign --verify --deep --strict` 通过。Redis 摘要允许原会话正常刷新 `lastAccessedTime`；验证过程中保留该活动，不重置访问时间。原 Web 服务、注册开关和基础设施继续运行，原有启动前备份及 `output/` 保留。

验收过程中补齐了客户端退出后服务端测试会话的清理；此前两条测试遗留会话也已按相同身份校验流程移除，原有会话保持不变。

仅保留可复用验收脚本、本记录及 `.testhub/` 中的脱敏结果。若清理失败，脚本会保留权限为 `0600` 的私有恢复清单，并将结果标记为失败；文件、会话未确认清理前保留账号数据，以便继续处理。

## 复验入口

- `scripts/desktop/accept-web-mac-account-sync.mjs`：真实 Web 页面与已安装 Mac 客户端的端到端流程。
- `scripts/desktop/qa-owned-account-database.mjs`：私人账号归属检查、数据库摘要与事务清理。
- `scripts/desktop/qa-owned-account-sessions.mjs`：精确识别测试会话、退出及原有会话摘要核对。
- 复用 `scripts/desktop/offscreen-mac-qa.mjs`，不修改安装程序。

先通过本机安全方式设置 `TESTHUB_QA_MYSQL_PASSWORD`、`TESTHUB_QA_REDIS_PASSWORD`，然后提供当前环境的真实容器名及已安装的无头 Chromium 路径。输出路径必须为新文件：

```bash
node scripts/desktop/accept-web-mac-account-sync.mjs \
  --app /Applications/TestHub.app \
  --web http://127.0.0.1:5173 \
  --chromium /absolute/path/to/chrome-headless-shell \
  --mysql-container testhub-mysql-1 \
  --database testhub --database-user testhub \
  --redis-container testhub-redis-1 \
  --minio-container testhub-minio-1 --bucket testhub-files \
  --output .testhub/web-mac-account-acceptance-new.json
```

本轮最终脱敏报告：`.testhub/web-mac-account-acceptance-20261022-final-04.json`。原始用户名、密码、Cookie、项目 ID 和业务正文不写入该报告。

新增脚本通过 ESLint 与 Node 语法检查；Git 差异空白检查通过。本轮没有修改业务功能、重新打包或提交代码。
