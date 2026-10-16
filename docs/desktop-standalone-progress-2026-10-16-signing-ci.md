# 签名模块提交、后续验收与凭据办理状态

本文记录 `92bc93a` 阶段的提交、CI 阻断及签名准备情况。后续已决定暂缓证书和公证，并完成 Mac r2 预览包的[本机安装](desktop-local-installation-2026-10-16.md)。下文的证书条件只适用于将来的正式签名发布，Windows 与 Intel Mac 验收继续暂缓。

2026-10-16，按用户明确授权，已将上一审核通过模块的 10 个代码／测试／文档文件提交并推送 GitHub main：`92bc93a861d2ca41ddc89a9ca9edbac111e14035`。本机 HEAD 与远端 main 一致，既有 `output/` 未提交或清理；本阶段新增的凭据办理指引和此状态报告保留在本地，未将它们作为已提交代码的内容。本轮没有新建分支、tag 或正式 Release。

## 已实际完成

- 提交 `build: verify native signing before desktop release uploads` 并推送 main；已审核实现内容的摘要与上轮验收记录匹配，未夹带测试产物或凭据。
- 触发 [verify](https://github.com/MrLimingbo/TestHub/actions/runs/38840726297)和 [desktop-release](https://github.com/MrLimingbo/TestHub/actions/runs/38840760194)。两轮均由 GitHub 在任何步骤执行前拒绝，原生矩阵未运行、正式发布跳过；错误注解提示账号近期付款失败或支出上限需要调整。这是提供方返回的二选一原因，未据此断言具体账单明细，也未修改付款方式或预算、反复重试。
- 补充本地 lint、前端 305 项、Vue 类型检查和隔离 Vite 生产构建，全部通过；输出仅在本阶段自有临时目录生成并已清理，根目录原有 dist 未覆盖。这些结果不替代未执行的 MySQL／安全镜像／三架构原生 CI。
- 再次只按名称核对仓库 Secret，七项全部缺失；只读本机公共签名身份检查为 0，未导出私钥。用户已确认尚未准备开发者账号及签名证书，要求办理指引，故未填入任何占位 Secret。
- 已整理 [账号、证书和安全配置办理指引](desktop-signing-credentials-setup.md)：补齐适用于中国大陆个人开发者的 Apple Developer App 注册步骤；Microsoft 公共签名不适用于该申请范围，SSL.com 个人云签名列为待确认地区资格的询价候选，并附未代发的询问模板。新后端所需变量可能取代当前两个 Windows PFX 字段，不能为了凑齐七项而绕开真实凭据。

## 清理与保留

两轮 CI 的 artifact 数均为 0，无安装器、数据库或其他远程测试产物可清理。隔离前端构建及自有进程已清理；原有 TestHub 安装 ASAR 摘要保持不变，当前本地运行时完整性通过并保留，旧运行时副本为 0。没有启动应用窗口、改动真实业务数据库、注册自托管 Runner 或更改仓库公开性。脱敏详情和后续恢复点见 `.testhub/signing-native-ci-module-20261016/final-status.json`。

## 将来恢复原生 CI 或正式签名发布的外部条件

1. 账号持有人核对 GitHub 账单／支付状态和 Actions 预算，解除提供方拒绝后，再重跑上述同一提交的失败任务。不要将本次未启动误记为测试通过。
2. 用户按中国大陆个人流程完成 Apple Developer 入会、Developer ID Application 及 App 专用密码准备；Windows 先取得供应商对中国大陆个人及无人值守 CI 的受理确认，再选定服务。身份验证和付费开通不能由默认工程推荐选项产生。
3. 获得真实凭据后安全配置 Secrets，并继续原生签名／公证、签名后启动、安装与 Gatekeeper／SmartScreen 验收。`distributionReady=false` 保持不变，尚未配置凭据或完成正式分发验收。
