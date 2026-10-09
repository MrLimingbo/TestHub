package io.testhub.platform.operations;

import io.testhub.platform.storage.ObjectStorage;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import io.testhub.platform.security.SecretRekeyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import io.testhub.platform.shared.runtime.RuntimeStreams;
import org.springframework.stereotype.Service;

@Service
public class OperationsService {
    private final AccessService access;
    private final boolean desktop;
    private final DataSource dataSource;
    private final RuntimeStreams streams;
    private final ObjectStorage storage;
    private final String bucket;
    private final boolean secureCookie;
    private final List<String> allowedOrigins;
    private final SecretCipher cipher;
    private final SecretRekeyService rekey;
    private final int capacityRequestCount;
    private final int capacityConcurrency;
    private final int capacityP95LatencyMs;
    private final double capacityMaxErrorPercent;

    public OperationsService(AccessService access, DataSource dataSource, RuntimeStreams streams, ObjectStorage storage,
                             @Value("${testhub.storage.bucket}") String bucket,
                             @Value("${server.servlet.session.cookie.secure:false}") boolean secureCookie,
                             @Value("${testhub.security.cors.allowed-origins}") List<String> allowedOrigins,
                             @Value("${testhub.operations.capacity.request-count:60}") int capacityRequestCount,
                             @Value("${testhub.operations.capacity.concurrency:6}") int capacityConcurrency,
                             @Value("${testhub.operations.capacity.p95-latency-ms:500}") int capacityP95LatencyMs,
                             @Value("${testhub.operations.capacity.max-error-percent:1}") double capacityMaxErrorPercent,
                             SecretCipher cipher, SecretRekeyService rekey, org.springframework.core.env.Environment environment) {
        this.desktop = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("desktop"));
        this.access = access;
        this.dataSource = dataSource;
        this.streams = streams;
        this.storage = storage;
        this.bucket = bucket;
        this.secureCookie = secureCookie;
        this.allowedOrigins = allowedOrigins.stream().map(String::trim).filter(item -> !item.isBlank()).toList();
        this.cipher = cipher;
        this.rekey = rekey;
        this.capacityRequestCount = capacityRequestCount;
        this.capacityConcurrency = capacityConcurrency;
        this.capacityP95LatencyMs = capacityP95LatencyMs;
        this.capacityMaxErrorPercent = capacityMaxErrorPercent;
    }

    public OperationsOverview overview(AuthenticatedPrincipal principal) {
        access.requireSystemAdmin(principal);
        List<OperationsOverview.Component> components = new ArrayList<>();
        components.add(databaseHealth());
        components.add(check("cache", streams.label(), streams::check));
        components.add(check("storage", storage.label(), this::storageReady));
        boolean healthy = components.stream().allMatch(component -> "UP".equals(component.status()));
        List<OperationsOverview.Control> controls = new ArrayList<>(List.of(
                new OperationsOverview.Control("request-id", "请求追踪", "ENABLED", "所有 HTTP 响应都带有 X-Request-Id。"),
                new OperationsOverview.Control("csrf", "浏览器 CSRF 防护", "ENABLED", "Cookie 会话写操作必须提交防伪令牌。"),
                new OperationsOverview.Control("security-headers", "安全响应头", "ENABLED", "API 响应启用 CSP、Referrer-Policy、Permissions-Policy 与防点击劫持策略。"),
                new OperationsOverview.Control("session-cookie", "安全 Cookie", secureCookie || desktop ? "ENABLED" : "ATTENTION",
                        desktop ? "本机回环 HTTP 使用隔离会话；连接账号服务时沿用该服务的 Cookie 安全策略。" : secureCookie ? "Secure Cookie 已启用。" : "当前环境未启用 Secure Cookie；生产 HTTPS 部署必须设置 TESTHUB_SECURE_COOKIE=true。"),
                new OperationsOverview.Control("cors", "浏览器来源白名单", "ENABLED", "已配置 " + allowedOrigins.size() + " 个允许来源。"),
                new OperationsOverview.Control("metrics", "Prometheus 指标", "ENABLED", "通过内部网络的 /actuator/prometheus 暴露。"),
                new OperationsOverview.Control("secret-key-ring", "数据加密密钥环", cipher.fallbackKeyCount() == 0 ? "ENABLED" : "ATTENTION",
                        cipher.fallbackKeyCount() == 0 ? "活动密钥标识为 " + cipher.activeKeyId() + "；没有加载旧密钥。"
                                : "活动密钥标识为 " + cipher.activeKeyId() + "；已加载 " + cipher.fallbackKeyCount() + " 个旧密钥，完成重加密验证后必须移除。"),
                new OperationsOverview.Control("secret-rekey-window", "密钥重加密窗口", rekey.enabled() ? "ATTENTION" : "ENABLED",
                        rekey.enabled() ? "重加密窗口已开启；仅可在维护窗口完成操作并在验证后关闭。"
                                : "重加密写入默认关闭；需要旧密钥回退、系统管理员确认和维护窗口。"),
                new OperationsOverview.Control("sbom", "CycloneDX 软件物料清单", "ENABLED",
                        "发布门禁会为 Platform API 和 Web 生产镜像生成可归档的 SBOM。"),
                new OperationsOverview.Control("image-scan", "容器镜像漏洞扫描", "ENABLED",
                        "发布门禁会阻断可修复的 HIGH 或 CRITICAL 级漏洞。"),
                new OperationsOverview.Control("release-gate", "供应链发布门禁", "ENABLED",
                        "CI 会校验生产 Compose、构建镜像、生成 SBOM 并执行漏洞扫描。"),
                new OperationsOverview.Control("helm-chart", "Kubernetes Helm 部署", "ENABLED",
                        "Chart 使用既有 Secret 注入运行时密钥，部署无状态 Web 与控制面，并提供就绪探针和资源边界。"),
                new OperationsOverview.Control("helm-rollback", "渐进升级与回滚防护", "ENABLED",
                        "升级使用原子等待；回滚仅回退工作负载，数据库迁移与数据恢复必须按受控流程单独处理。"),
                new OperationsOverview.Control("release-acceptance", "最终发布验收门禁", "ENABLED",
                        "本地门禁汇总测试、Helm、指标规则、容量基线和供应链证据；跳过镜像扫描时会显式标记为仅本地结果。"),
                new OperationsOverview.Control("production-drill", "真实环境发布演练", "ATTENTION",
                        "真实 Kubernetes、业务目标、Runner 和数据恢复演练需要独立变更审批；控制面不会代替执行。")));
        if (desktop) {
            controls.removeIf(control -> java.util.Set.of("sbom", "image-scan", "release-gate", "helm-chart", "helm-rollback", "release-acceptance", "production-drill").contains(control.key()));
            controls.add(new OperationsOverview.Control("desktop-lifecycle", "本机运行生命周期", "ENABLED",
                    "桌面管理后端启动、回环监听与退出；本地数据独立保存，执行由隔离 Runner 承接。"));
            controls.add(new OperationsOverview.Control("desktop-distribution", "安装包发行验收", "ATTENTION",
                    "此检查不能确认安装包签名、公证、各平台首启或第三方组件分发资格；正式发行需单独验收。"));
        }
        return new OperationsOverview(healthy ? "UP" : "ATTENTION", Instant.now(), List.copyOf(components), List.copyOf(controls),
                dataProtection(), capacity(), desktop ? "DESKTOP" : "SERVER");
    }

    private OperationsOverview.Component check(String key, String label, CheckedOperation operation) {
        try {
            operation.run();
            return new OperationsOverview.Component(key, label, "UP", "连接与基础读写检查正常。");
        } catch (Exception exception) {
            return new OperationsOverview.Component(key, label, "DOWN", "检查失败；请查看受保护的服务日志与监控告警。");
        }
    }

    private OperationsOverview.Component databaseHealth() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT 1")) {
            statement.execute();
            return new OperationsOverview.Component("database", connection.getMetaData().getDatabaseProductName(),
                    "UP", "连接与基础读写检查正常。");
        } catch (Exception exception) {
            return new OperationsOverview.Component("database", "数据库", "DOWN",
                    "检查失败；请查看受保护的服务日志与监控告警。");
        }
    }

    private void storageReady() throws Exception {
        storage.bucketExists(bucket);
    }

    private OperationsOverview.DataProtection dataProtection() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "SELECT version, description FROM flyway_schema_history WHERE success = TRUE "
                        + "ORDER BY installed_rank DESC LIMIT 1")) {
            try (var results = statement.executeQuery()) {
                if (!results.next()) return unavailableMigrationStatus();
                String version = results.getString("version");
                String description = results.getString("description");
                return new OperationsOverview.DataProtection("V" + version, "UP",
                        "Flyway 最近成功迁移：" + description + "。",
                        recoveryControls());
            }
        } catch (Exception exception) {
            return unavailableMigrationStatus();
        }
    }

    private OperationsOverview.DataProtection unavailableMigrationStatus() {
        return new OperationsOverview.DataProtection("—", "ATTENTION",
                "迁移状态暂不可用；请在受保护日志与数据库监控中排查。", recoveryControls());
    }

    private List<OperationsOverview.Control> recoveryControls() {
        return List.of(
                new OperationsOverview.Control("backup-verification", "备份完整性校验", "ATTENTION",
                        desktop ? "可通过桌面加密备份导出工作区；此接口未读取备份产物，不能确认已有备份或恢复演练。" : "尚未检测到外部备份凭据；使用受控脚本生成备份后执行 verify 校验。"),
                new OperationsOverview.Control("restore-guard", "恢复操作防护", "ENABLED",
                        desktop ? "恢复由原生桌面确认后离线执行，校验清单和加密档案；升级前自动创建保护快照。" : "恢复命令必须校验清单、停用 API 并明确传入确认参数；控制面不执行恢复。"),
                new OperationsOverview.Control("volatile-cache", streams.label() + "恢复", "ENABLED",
                        "Redis".equals(streams.label()) ? "Redis 快照仅供取证；恢复会清空会话和队列并由控制面重新建立。"
                                : "本地队列随工作区数据库持久保存；各执行模块按自身恢复规则处理，投递结果不明的通知需人工核对。"));
    }

    private OperationsOverview.CapacityProfile capacity() {
        String baseline = "默认基线：" + capacityRequestCount + " 次就绪探针、并发 " + capacityConcurrency
                + "、p95 不高于 " + capacityP95LatencyMs + " ms、失败率不高于 "
                + String.format(Locale.ROOT, "%.2f", capacityMaxErrorPercent) + "% 。";
        return new OperationsOverview.CapacityProfile("ATTENTION", baseline, List.of(
                new OperationsOverview.Control("capacity-baseline", "受控容量基线", "ENABLED",
                        desktop ? "就绪探针用于本机依赖检查；默认基线参数不代表已完成本机业务负载测试。" : "容量脚本仅访问 /actuator/health/readiness；结果应作为部署证据归档。"),
                new OperationsOverview.Control("resource-alerts", "资源告警阈值", desktop ? "ATTENTION" : "ENABLED",
                        desktop ? "本机不自动部署 Prometheus 告警服务；执行容量和组件健康需分别验证。" : "Prometheus 规则覆盖 API 可用性、5xx、p95 延迟、JVM 堆和数据库连接池饱和度。"),
                new OperationsOverview.Control("recovery-drill", "故障恢复演练", "ATTENTION",
                        "恢复演练必须在隔离环境完成，并校验备份、就绪探针与数据恢复后的关键业务流。")));
    }

    @FunctionalInterface
    private interface CheckedOperation { void run() throws Exception; }
}
