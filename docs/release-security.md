# 发布供应链与密钥轮换

阶段 10 的发布门禁不会在控制面运行时下载扫描数据库、构建镜像或处理原始密钥；这些动作只在本地受控运维环境和 CI 中执行。

## 本地或 CI 发布门禁

在完成应用测试后执行：

```bash
chmod 700 scripts/security/*.sh
TESTHUB_SECURITY_ARTIFACT_DIR=artifacts/security \
  scripts/security/release-gate.sh
```

门禁会检查生产 Compose 配置、构建 Platform API 与 Web 镜像、为两者生成 CycloneDX JSON SBOM，并用 Trivy 阻断可修复的 `HIGH` / `CRITICAL` 漏洞。SBOM 位于 `artifacts/security/`，应作为发行版本的不可变构件归档。

单独使用：

```bash
scripts/security/generate-sbom.sh testhub/platform-api:0.1.0 artifacts/platform-api.cdx.json
scripts/security/scan-image.sh testhub/platform-api:0.1.0
```

扫描器镜像使用固定版本；升级扫描器、基础镜像或漏洞阈值应通过常规变更评审。`--ignore-unfixed` 只忽略上游没有可用修复版本的问题，不能替代风险评估和基础镜像升级。

GitHub Actions 中 `web`、`platform-api` 与 `release-gate` 三项检查应设置为目标分支的必需状态；`release-gate` 会上传 SBOM 产物。分支保护规则属于仓库管理员的外部配置，不由代码仓库静默修改。

## 数据加密密钥轮换

新写入的密文格式为 `v2:<key-id>:<payload>`。旧 `v1` 密文在轮换期间仍可解密，完成重加密后会被转换到当前 v2 主密钥。密钥 ID 不属于敏感数据；Base64 AES 密钥本身绝不能写进仓库、工单、审计详情或命令行日志。

轮换采用两阶段流程：

1. 完成经过验证的备份，并建立维护窗口。生成新 AES 密钥和新的 `TESTHUB_ENCRYPTION_KEY_ID`。
2. 在所有 API 副本上部署新主密钥，同时把旧密钥作为 `TESTHUB_PREVIOUS_ENCRYPTION_KEYS=old-id:Base64OldKey` 配置；保持 `TESTHUB_SECRET_REKEY_ENABLED=false`，确认所有副本能读取业务密文。
3. 在维护窗口启用 `TESTHUB_SECRET_REKEY_ENABLED=true`，确保只有一个控制面写入者。系统管理员调用受保护的 `POST /api/v1/operations/secret-rekey`，请求体只包含固定确认文本 `REKEY_PERSISTED_SECRETS`，不会传输或返回任何密钥值。
4. 核对审计事件 `PERSISTED_SECRETS_REKEYED`、运维页面的密钥环状态、解密相关业务冒烟测试和备份。该操作在一个数据库事务中更新项目环境密钥、自动化 Webhook、通知签名、投递快照和企业适配器配置。
5. 关闭重加密窗口，移除 `TESTHUB_PREVIOUS_ENCRYPTION_KEYS`，滚动重启所有 API 副本，并再次验证密钥读取。

重加密在以下条件下会拒绝执行：窗口未开启、调用者不是系统管理员、确认文本不匹配、没有旧密钥回退配置，或任意现存密文无法解密。遇到失败应保持 API 停止并从备份恢复或修正密钥环，不要移除旧密钥。
