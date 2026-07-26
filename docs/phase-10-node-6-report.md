# 阶段 10 · 节点 6：最终发布候选验收

## 交付内容

- 新增 `scripts/release/final-acceptance.sh`，将脚本语法、生产 Compose、Helm、Prometheus 告警规则、Platform API、Web、受控容量基线、恢复计划、镜像、CycloneDX SBOM 与漏洞扫描收敛为一个可重复的本地发布验收门禁。
- 门禁会主动选择 JDK 21；图形化桌面启动未继承 `JAVA_HOME` 时，优先使用系统注册的 JDK，再回退到 Homebrew 的 JDK 21 路径。缺失 `javac` 时立即失败，不会把 JRE 误用于编译验证。
- `--skip-supply-chain` 仅用于快速本地调试，产物明确标记为 `LOCAL_ONLY`；默认门禁包含镜像供应链检查，成功产物标记为 `CANDIDATE`。
- 新增[生产发布验收矩阵](production-acceptance-matrix.md)，将已有自动化、本地候选门禁和真实环境验收责任分开记录。
- 新增[最终发布演练与验收手册](production-release-acceptance.md)，定义本地候选、变更评审、隔离集群演练和生产窗口的顺序。
- 运行与发布页面增加“最终发布验收门禁”和“真实环境发布演练”控制项，后者明确标识为需要独立变更审批。

## 候选验收记录

2026-07-24 已对本机控制面运行完整默认门禁：

```bash
scripts/release/final-acceptance.sh \
  --url http://127.0.0.1:8080/actuator/health/readiness \
  --artifact-dir /tmp/testhub-node6-candidate
```

结果为 `PASSED / CANDIDATE`。证据保存在本机临时受限目录 `/tmp/testhub-node6-candidate`，包含：

- `acceptance.json`：最终候选结论与证据索引。
- `capacity-baseline.json`：24 个就绪探针、并发 4、P50 2 ms、P95 9 ms、0 错误；阈值为 P95 不高于 500 ms、错误率不高于 1%。
- `recovery-drill-plan.txt`：非破坏性恢复演练计划。
- `security/platform-api.cdx.json` 与 `security/web.cdx.json`：两份 CycloneDX SBOM；两个生产镜像漏洞扫描均为 0 发现。

同时执行了 `--skip-supply-chain` 的快速本地门禁，结果正确标记为 `LOCAL_ONLY`，以验证跳过选项不会被误用为候选发布结论。

## 验证记录

- 完整候选门禁通过：Platform API 测试、前端 lint/Vitest/类型检查/生产构建、生产 Compose 渲染、Helm lint/template、Prometheus 规则检查、容量基线、恢复计划、镜像构建、SBOM 与漏洞扫描。
- `bash -n scripts/security/*.sh scripts/operations/*.sh scripts/deployment/*.sh scripts/release/*.sh infra/backup/*.sh` 通过。
- `scripts/release/final-acceptance.sh --help` 与无效 URL 拒绝路径通过。
- `git diff --check` 通过。
- 浏览器预览：`/operations` 显示最终发布验收门禁为已启用，真实环境发布演练为需关注；页面未暴露密钥、集群地址或任何部署凭据。

Vitest 仍会输出 Monaco 上游缺失 source map 的既有警告，且 Vite 会给出大于 500 kB 的分包建议；两者均未影响 lint、测试、类型检查或生产构建的通过结果。

## 发布边界与后续动作

阶段 10 的代码侧交付已完成，当前状态是“本地生产候选已验证”，不是“已在生产发布”。本节点没有连接 Kubernetes 集群、创建 Secret、执行 Helm `apply`、访问外部业务目标、启动真实 Runner、恢复真实数据或切换生产流量。

真实发布必须获得单独的变更授权，并按[最终发布演练与验收手册](production-release-acceptance.md)在隔离集群完成 Ingress、Runner、数据恢复、告警路由、回滚及经授权业务目标的验证后，才能进入生产窗口。
