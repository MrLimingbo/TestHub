# 阶段 2 · 节点 2.3 验收报告：RBAC 与个人访问令牌

完成日期：2026-07-11

## 本节点范围

- 组织、项目和成员上下文查询。
- 组织级系统管理员与项目级 RBAC。
- 项目成员角色维护。
- 面向 CLI/CI 的项目范围个人访问令牌（PAT）：创建、单次明文返回、哈希存储、过期与撤销。
- Bearer Token 认证与项目范围限制。
- 项目设置中的真实成员与访问令牌界面。

## 权限模型

- `SYSTEM_ADMIN` / `ORG_ADMIN`：组织范围内的管理权限。
- `PROJECT_ADMIN`：管理项目成员与访问令牌。
- `TEST_LEAD`、`TESTER`、`VIEWER`：项目业务角色，为后续测试模块授权预留。
- PAT 绑定用户和单一项目；支持 `project:read`、`project:write`、`run:execute` 范围。Bearer 请求只可进入令牌所属项目，并跳过 Cookie 会话专用的 CSRF 校验。

## 本地演示数据

- `admin@testhub.local`：系统管理员。
- `lead@testhub.local`：交易中台 V3 的测试负责人。
- `tester@testhub.local`：交易中台 V3 的测试工程师。
- 以上账号密码均为 `TestHub2026!`，仅用于本地开发。

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过。覆盖管理员创建项目令牌并使用 Bearer 读取上下文、测试工程师被拒绝修改成员、登录/退出会话回归。
- 测试创建的令牌会在测试结束后清理，避免污染本地演示数据。
- 真实 HTTP：管理员创建 PAT 后，带 `Authorization: Bearer` 的项目上下文请求返回 200；PAT 数据库仅保存 SHA-256 哈希。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过。
- 浏览器预览：管理员登录后打开项目设置，已验证成员列表、空令牌状态与“新建令牌”表单交互。

## 用户预览

- Web：<http://localhost:5173/project-settings>
- 进入后可使用“添加成员”将预置账号授权为任一项目角色，或在“新建令牌”中创建、保存、撤销 PAT。

下一节点（2.4）将在确认后才开始，内容为项目参数、环境密钥、系统配置和审计日志。
