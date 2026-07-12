# 阶段 2 · 节点 2.4.2 验收报告：环境密钥

完成日期：2026-07-12

## 本节点范围

- 按组织、项目、环境隔离的环境密钥新增、覆盖、元数据查询与删除。
- AES-GCM 加密存储，密文格式为 `v1:<Base64Url(nonce+ciphertext)>`。
- 密钥读取接口和 Web 页面仅返回名称、环境、更新时间等元数据，不返回密钥值。
- 项目管理员、测试负责人可写；项目成员可读元数据；Bearer Token 写入要求 `project:write`。

## 安全约束

- `project_parameter` 中的普通参数查询明确排除 `secret = TRUE`，避免密钥进入参数接口。
- 每次加密使用 12 字节随机 nonce 与 128 位 GCM 认证标签。
- 服务启动时校验 `TESTHUB_ENCRYPTION_KEY`：必须为 Base64 编码，解码后为 16、24 或 32 字节 AES 密钥。
- 仓库配置的默认键仅用于本地开发；生产部署必须覆盖该环境变量。
- 当前版本不提供密钥回显或解密接口，降低日志、浏览器和接口泄露风险。

## 接口

- `GET /api/v1/projects/{projectId}/secrets?environment={code}`
- `POST /api/v1/projects/{projectId}/secrets`
- `DELETE /api/v1/projects/{projectId}/secrets/{secretId}`

## 验证结果

- `./mvnw -q -pl services/platform-api test`：通过。覆盖密钥创建、列表不含 `value`、数据库密文不包含原文、删除和测试数据清理。
- 真实 HTTP：创建返回 `204`；读取元数据不含 `value`；数据库保存值以 `v1:` 开头且不含原始密钥；删除返回 `204`。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build`：通过。
- 浏览器：环境密钥页面真实加载、空状态、导航及新建/覆盖密钥表单已验证；未在浏览器中提交测试密钥。

## 用户预览

- Web：<http://localhost:5173/project-settings/secrets>
- 新建密钥后只会看到名称、环境和更新时间；请在提交前确认密钥值可安全保存，因为保存后无法回显。

下一步等待确认：节点 2.4.3 系统配置与审计日志。
