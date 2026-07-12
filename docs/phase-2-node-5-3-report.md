# 阶段 2 · 节点 2.5.3 验收报告：MinIO 文件服务

完成日期：2026-07-12

## 本节点范围

- 项目文件列表、上传、元数据查询、下载和删除。
- MinIO bucket `testhub-files` 自动检查与创建。
- MySQL `file_object` 元数据与 MinIO 对象分离存储。
- 上传文件名取安全 basename，缺失 Content-Type 时使用默认类型；单文件大小受 20 MB 限制。
- 本节点只建设控制面文件服务，不接入 Runner、报告附件编排或用户代码执行。

## 权限与安全边界

- 项目成员可读取文件列表、元数据和下载内容。
- 系统管理员、组织管理员、项目管理员、测试负责人和测试工程师可上传、删除文件。
- 文件对象键由项目 ULID 和文件 ULID 生成，原始文件名仅作为元数据保存，避免路径穿越。
- MinIO 凭据通过环境配置注入，MySQL 不保存文件二进制内容。
- 控制面不执行用户脚本，文件服务不改变 Runner 隔离边界。

## 接口

- `GET /api/v1/projects/{projectId}/files`
- `POST /api/v1/projects/{projectId}/files`（multipart 字段 `file`）
- `GET /api/v1/projects/{projectId}/files/{fileId}`
- `GET /api/v1/projects/{projectId}/files/{fileId}/download`
- `DELETE /api/v1/projects/{projectId}/files/{fileId}`

## 验证结果

- Java 21 Maven 测试通过：9 个权限与流程测试、2 个认证测试、1 个应用启动测试均无失败。
- 真实 HTTP/MinIO 烟测通过：上传 README 副本为 `testhub-preview.md`，列表可见，下载 HTTP 200 且字节一致，删除成功，最终列表为空。
- `npx --yes pnpm@10.13.1 lint`、`test`、`build` 和 `git diff --check` 均通过；保留既有 Monaco source map 和大分包提示。
- 浏览器已打开项目文件页并验证空状态，控制台错误为 `[]`。烟测文件已删除，因此页面当前为空是预期结果。

## 用户预览

- 项目文件：<http://localhost:5173/project-settings/files>

下一步等待确认：阶段 2.6 基础平台验收。
