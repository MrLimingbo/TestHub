# TestHub 签名凭据办理与配置指引

核对日期：2026-10-16。本指引适用于**中国大陆个人开发者**。当前发行方案已暂缓 Developer ID、Apple 公证和 Windows 商业签名，先使用不含发行者证书的预览包；本文保留为将来启用正式签名的办理参考。Mac r2 已完成本机安装，见 [安装记录](desktop-local-installation-2026-10-16.md)。

上述核对日期的仓库凭据检查显示，`MrLimingbo/TestHub` 缺少全部 7 项签名 Secrets；本机只读身份检查也没有发现可用 Developer ID Application。未创建、购买或上传任何证书及凭据。这些凭据用于正式签名发布，不是运行现有本地预览版的前提。

## 将来启用正式签名时的办理顺序

1. 按中国大陆个人开发者办理 Apple 会员；Windows 先取得供应商对“中国大陆个人＋GitHub 托管 Runner 自动签名”的受理确认，再决定是否购买。
2. 先办理 Apple Developer Program，取得 Mac 所需的五项资料。Windows 同时核对服务资格，选定适合 GitHub 托管 Runner 的云／HSM 签名方案后再办理。
3. 另外检查 [GitHub 账号账单与额度](https://github.com/settings/billing)。本轮 `verify` 和 `desktop-release` 均在任何步骤执行前被 GitHub 拒绝，原因提示为付款失败或支出限制。账号费用问题与代码、证书办理是三个不同事项；新建签名凭据不会解除这个阻断。仅核对实际付款状态及 Actions 预算，不需要更改仓库公开性。依据 [GitHub Actions 计费说明](https://docs.github.com/en/billing/concepts/product-billing/github-actions)。本次没有修改账单、预算或付款方式。

## Mac：先准备五项资料

1. 按 [Apple 中国大陆个人注册流程](https://developer.apple.com/cn/help/account/membership/enrolling-in-the-app/)，使用最新版 **Apple Developer App → 账户 → 现在注册**，完成身份验证，实体类型选择**个人**。同一设备完成全流程，Apple 账号开启双重认证、设备登录 iCloud；证件和自拍仅在 Apple 官方 App 内提交。官方当前页面列示 **¥688／年，自动续订**，实际金额以 App 确认页为准，申请及付款由账号持有人完成。
2. 入会成功后，在 [Developer Account](https://developer.apple.com/account/) 的 Certificates, Identifiers & Profiles 新建 **Developer ID Application** 证书，按官方流程在本机创建 CSR、上传 CSR、下载并安装证书。当前 TestHub 以 DMG 分发 `.app`，这里需要的是应用签名证书。证书类型和申请步骤见 [Apple Developer ID 说明](https://developer.apple.com/help/account/certificates/create-developer-id-certificates/)。
3. 确认本机同时持有证书对应的私钥，再导出为设置了强密码的 `.p12` 签名身份。仅下载 `.cer` 不足以在 CI 签名。可在系统自带“钥匙串访问”中选中对应的证书／私钥身份，使用“文件 → 导出项目”并选择 PKCS#12 格式、设置密码，参见 [Apple 钥匙串导出说明](https://support.apple.com/guide/keychain-access/import-and-export-keychain-items-kyca35961/mac)。若已使用 Xcode 管理签名，也可在 Xcode → Settings → Accounts → 所属团队 → Manage Certificates 中导出，步骤见 [签名身份导出文档](https://developer.apple.com/documentation/xcode/sharing-your-teams-signing-certificates)。保存好 `.p12` 和导出密码，不放入项目目录或 Git。
4. 到 [Apple Account](https://account.apple.com/) 的登录与安全设置生成供 TestHub CI 使用的 **App 专用密码**。这是 `APPLE_APP_SPECIFIC_PASSWORD`，不是 Apple 账号的日常登录密码；官方操作步骤见 [Apple 支持](https://support.apple.com/en-us/102654)。
5. 在 Developer Account → Membership details 查到 **Team ID**，应为 10 位字符。来源见 [Apple Team ID 说明](https://developer.apple.com/help/glossary/team-id/)。

| 仓库 Secret | 准备的实际内容 |
| --- | --- |
| `MACOS_CSC_LINK` | 含匹配私钥、以密码保护的 `.p12` 文件的 Base64 内容；由本机工具直接送入 GitHub，不在聊天中展示 |
| `MACOS_CSC_KEY_PASSWORD` | 上述 `.p12` 的导出密码 |
| `APPLE_ID` | 有权执行该团队公证的 Apple 账号登录标识 |
| `APPLE_APP_SPECIFIC_PASSWORD` | 该账号新生成的 App 专用密码 |
| `APPLE_TEAM_ID` | 与 Developer ID Application 证书一致的 Team ID |

## Windows：先核对资格，再选择签名后端

当前工作流的 `WINDOWS_CSC_LINK` 与 `WINDOWS_CSC_KEY_PASSWORD` 是**可导出 PKCS#12／PFX 文件**的接入方式。它们不是 GitHub 自动生成的值，也不表示新办公共代码签名证书一定能导出 PFX。DigiCert 的官方说明指出，新签发的代码签名私钥需保存在符合要求的硬件或 HSM 中，旧式 PFX 导出指引仅适用于符合条件的旧证书。参见 [私钥保管要求](https://knowledge.digicert.com/general-information/new-private-key-storage-requirement-for-standard-code-signing-certificates-november-2022)与 [导出适用范围](https://knowledge.digicert.com/tutorials/windows-exporting-your-code-signing-certificate)。

本项目优先评估支持自动 CI 的公共信任云签名／云 HSM 服务；购买前核实中国大陆个人受理资格、Windows EXE 签名用途及 GitHub 托管 Runner 接入方式。

- **排除 Microsoft Artifact Signing Public Trust**：官方目前要求个人申请人位于美国或加拿大，与你已确认的居住地区不符；Private Trust／测试证书不能代替公开发行签名。依据 [Microsoft 快速开始](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart)和 [信任模型](https://learn.microsoft.com/en-us/azure/artifact-signing/concept-trust-models)。
- **优先询价候选：SSL.com IV Code Signing + eSigner for Code**：官方明确提供个人 IV 证书及 GitHub Actions 云签名，私钥由云 HSM 保管，无需向 Runner 分发硬件令牌。依据 [个人代码签名](https://www.ssl.com/products/software-integrity/code-signing/iv/)和 [eSigner for Code](https://www.ssl.com/products/software-integrity/signing-service/)。但未从已核对的官方资料确认中国大陆个人的具体受理条件，因此这还不是可以直接下单的结论。证书与 eSigner 是分别办理的产品，总费用需包括证书、云签名订阅、签名次数与可能的附加费用，不能只比较证书标价。
- **其他公共 CA 的云／HSM 签名**：先向 CA 核实是否受理你的主体和地区、是否提供托管云密钥以及当前受支持的 GitHub 集成。可参考 [DigiCert 云密钥方案](https://knowledge.digicert.com/solution/digicert-keylocker)和 [官方 GitHub 集成文档](https://docs.digicert.com/en/digicert-keylocker/ci-cd-integrations-and-deployment-pipelines/plugins/github.html)；后者已公告旧集成退役及新 Binary Signing 路线，不应直接照搬旧教程。此处是接入参考，不代表已经核实你符合该供应商的申请资格，也未替你选择套餐或下单。
- **仅在确有可合法导出的有效 PFX 时**：才能继续用现有 `WINDOWS_CSC_LINK`（PFX 的 Base64 内容）和 `WINDOWS_CSC_KEY_PASSWORD`（该文件密码）。若私钥在硬件／云端，不要求导出私钥，而是适配相应签名接口。

新证书仍可能出现 SmartScreen 提示，签名不保证立即获得下载信誉，参见上述 SSL.com 个人证书说明。最终需要的 Secret 数量及名称随云签名后端确定；不能为了凑齐原来的 7 项而填写占位值。

### 可直接使用的供应商询问模板

以下是草稿，尚未代发。可通过 [SSL.com 官方联系入口](https://www.ssl.com/contact_us/)向其确认；若其他 CA 受理个人申请，也使用相同问题核实。先确认资格及完整报价，再决定购买。

> 我是居住在中国大陆的个人开发者，没有公司主体，计划以个人名义公开分发 TestHub Windows 桌面应用。应用基于 Electron，需在 GitHub 托管的 Windows Runner 中，无人值守地为应用 EXE 和 NSIS 安装器进行 Authenticode 签名并加可信时间戳。
>
> 请确认：
> 1. 是否受理中国大陆个人的 IV Code Signing 与 eSigner for Code？支持哪些身份证明、地址证明及验证方式，是否需要英文翻译？
> 2. 是否支持临时 GitHub 托管 Runner、无人值守认证及上述两类 EXE？请提供当前官方集成文档和所需凭据清单。
> 3. 证书、云签名订阅、签名次数、超额、税费及其他费用合计多少？有无最低期限、自动续费或额外验证费用？
> 4. 若无法通过地区或身份验证，退款及取消政策是什么？

收到答复后，只需提供不含个人身份资料及凭据的受理结论、报价和官方集成链接，即可确定 Windows 接入方案。

## 准备好后如何安全配置

仓库入口：[TestHub → Settings → Secrets and variables → Actions](https://github.com/MrLimingbo/TestHub/settings/secrets/actions)。密码只在你自己的终端隐藏输入或 GitHub 的 Secret 输入框中填写。账号证件、付款资料、私钥、证书文件内容和密码都不通过聊天传递。

拿到签名身份后，只需在聊天中确认“已准备好”、提供本机证书文件路径，以及 Windows 所选签名服务名称；我再协助完成本机格式／用途核对、直接写入 GitHub Secrets、核对名称是否齐全，并在账单阻断解除后继续原生 CI。GitHub CLI 的 `gh secret set` 支持从标准输入读取并在本地加密后上传，可避免把密码放进命令参数或 `.env` 文件；依据 [GitHub CLI 官方说明](https://cli.github.com/manual/gh_secret_set)。当前没有执行任何 Secret 写入。

配置名称齐全仅代表配置动作完成，不能证明证书受信任或公证成功。仍须通过真实 Developer ID／Authenticode、签名后启动与安装、Gatekeeper／SmartScreen 和业务持久化验收后再决定正式发行；本轮不会自动创建 tag 或 Release。
