package io.testhub.platform.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExtensionRegistryService {
    private static final Set<String> PLUGIN_TYPES = Set.of("RUNNER", "INTEGRATION", "REPORTER");
    private static final Set<String> PLUGIN_PERMISSIONS = Set.of("runner:task-consume", "runner:artifact-write",
            "integration:outbound", "integration:credentials-read", "report:generate", "project:read");
    private final ExtensionRegistryRepository registry;
    private final EnterpriseAdapterCatalog adapters;
    private final AccessService access;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final ObjectMapper json;

    public ExtensionRegistryService(ExtensionRegistryRepository registry, EnterpriseAdapterCatalog adapters, AccessService access,
                                    AuditService audit, SecretCipher cipher, ObjectMapper json) {
        this.registry = registry;
        this.adapters = adapters;
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.json = json;
    }

    public List<InstalledPlugin> plugins(AuthenticatedPrincipal principal) {
        requireSignedIn(principal);
        return registry.findPlugins();
    }

    public List<PluginSigningKey> signingKeys(AuthenticatedPrincipal principal) {
        access.requireSystemAdmin(principal);
        return registry.findSigningKeys();
    }

    public List<EnterpriseAdapterDefinition> adapterDefinitions(AuthenticatedPrincipal principal) {
        requireSignedIn(principal);
        return adapters.definitions();
    }

    public List<EnterpriseAdapterConfiguration> adapterConfigurations(AuthenticatedPrincipal principal) {
        requireSignedIn(principal);
        return registry.findAdapters().stream().map(this::publicAdapter).toList();
    }

    @Transactional
    public PluginSigningKey registerSigningKey(AuthenticatedPrincipal principal, SigningKeyInput input) {
        access.requireSystemAdmin(principal);
        String keyId = normalizeKeyId(input.keyId());
        String displayName = required(input.displayName(), 120, "签名密钥名称");
        String publicKey = required(input.publicKeyBase64(), 1024, "签名公钥");
        verifyPublicKey(publicKey);
        String id = UlidCreator.getUlid().toString();
        try {
            registry.insertSigningKey(id, keyId, displayName, publicKey, principal.userId());
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "签名密钥标识已存在");
        }
        audit.record(principal, null, null, "PLUGIN_SIGNING_KEY_REGISTERED", "PLUGIN_SIGNING_KEY", id,
                Map.of("keyId", keyId, "displayName", displayName));
        return registry.findSigningKeys().stream().filter(key -> key.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void setSigningKeyEnabled(AuthenticatedPrincipal principal, String id, boolean enabled) {
        access.requireSystemAdmin(principal);
        PluginSigningKey key = registry.findSigningKeys().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "签名密钥不存在"));
        if (key.enabled() == enabled) return;
        registry.setSigningKeyEnabled(id, enabled);
        audit.record(principal, null, null, enabled ? "PLUGIN_SIGNING_KEY_ENABLED" : "PLUGIN_SIGNING_KEY_REVOKED",
                "PLUGIN_SIGNING_KEY", id, Map.of("keyId", key.keyId()));
    }

    @Transactional
    public InstalledPlugin installPackage(AuthenticatedPrincipal principal, PackageInput input) {
        access.requireSystemAdmin(principal);
        String manifestJson = required(input.manifestJson(), 50_000, "插件清单");
        String signatureBase64 = required(input.signatureBase64(), 4096, "插件签名");
        PluginManifest manifest = parseAndValidateManifest(manifestJson);
        ExtensionRegistryRepository.StoredSigningKey signer = registry.findEnabledSigningKey(normalizeKeyId(input.signerKeyId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "未找到已启用的可信签名密钥"));
        verifySignature(signer.publicKeyBase64(), manifestJson, signatureBase64);
        String checksum = sha256(manifestJson);
        InstalledPlugin current = registry.findPluginByKey(manifest.pluginKey()).orElse(null);
        String pluginId;
        if (current == null) {
            pluginId = UlidCreator.getUlid().toString();
            registry.insertPlugin(pluginId, manifest, signer.key().id(), checksum, manifestJson, principal.userId());
        } else {
            if (compareVersions(manifest.version(), current.activeVersion()) <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "插件升级版本必须高于当前已安装版本");
            }
            pluginId = current.id();
            registry.updatePluginRelease(pluginId, manifest, signer.key().id(), checksum, manifestJson, principal.userId());
        }
        registry.insertRelease(UlidCreator.getUlid().toString(), pluginId, manifest.version(), signer.key().id(), checksum,
                manifestJson, signatureBase64, principal.userId());
        audit.record(principal, null, null, current == null ? "PLUGIN_INSTALLED" : "PLUGIN_UPGRADED", "PLATFORM_PLUGIN", pluginId,
                Map.of("pluginKey", manifest.pluginKey(), "version", manifest.version(), "signerKeyId", signer.key().keyId()));
        return registry.findPlugin(pluginId).orElseThrow();
    }

    @Transactional
    public InstalledPlugin setPluginEnabled(AuthenticatedPrincipal principal, String id, boolean enabled) {
        access.requireSystemAdmin(principal);
        InstalledPlugin plugin = registry.findPlugin(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "插件不存在"));
        if (("ENABLED".equals(plugin.status())) == enabled) return plugin;
        registry.setPluginStatus(id, enabled ? "ENABLED" : "DISABLED", principal.userId());
        audit.record(principal, null, null, enabled ? "PLUGIN_ENABLED" : "PLUGIN_DISABLED", "PLATFORM_PLUGIN", id,
                Map.of("pluginKey", plugin.pluginKey(), "version", plugin.activeVersion()));
        return registry.findPlugin(id).orElseThrow();
    }

    @Transactional
    public EnterpriseAdapterConfiguration saveAdapter(AuthenticatedPrincipal principal, String id, AdapterInput input) {
        access.requireSystemAdmin(principal);
        String type = adapters.requireDefinition(input.adapterType()).type();
        String displayName = required(input.displayName(), 120, "适配器名称");
        String endpoint = adapters.validateAndNormalizeEndpoint(type, input.endpoint());
        Map<String, String> configuration = adapters.validateConfiguration(type, input.configuration());
        String ciphertext = cipher.encrypt(write(configuration));
        String adapterId = id == null ? UlidCreator.getUlid().toString() : id;
        boolean creating = id == null;
        if (creating) registry.insertAdapter(adapterId, type, displayName, endpoint, ciphertext, input.enabled(), principal.userId());
        else {
            registry.findAdapter(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "企业适配器不存在"));
            registry.updateAdapter(adapterId, type, displayName, endpoint, ciphertext, input.enabled(), principal.userId());
        }
        audit.record(principal, null, null, creating ? "ENTERPRISE_ADAPTER_CREATED" : "ENTERPRISE_ADAPTER_UPDATED",
                "ENTERPRISE_ADAPTER", adapterId, Map.of("type", type, "enabled", input.enabled()));
        return publicAdapter(registry.findAdapter(adapterId).orElseThrow());
    }

    @Transactional
    public void deleteAdapter(AuthenticatedPrincipal principal, String id) {
        access.requireSystemAdmin(principal);
        EnterpriseAdapterConfiguration adapter = publicAdapter(registry.findAdapter(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "企业适配器不存在")));
        registry.deleteAdapter(id);
        audit.record(principal, null, null, "ENTERPRISE_ADAPTER_DELETED", "ENTERPRISE_ADAPTER", id,
                Map.of("type", adapter.adapterType()));
    }

    private EnterpriseAdapterConfiguration publicAdapter(ExtensionRegistryRepository.StoredAdapter adapter) {
        try {
            Map<String, String> configuration = json.readValue(cipher.decrypt(adapter.configurationCiphertext()), new com.fasterxml.jackson.core.type.TypeReference<>() { });
            return new EnterpriseAdapterConfiguration(adapter.id(), adapter.adapterType(), adapter.displayName(), adapter.endpoint(),
                    configuration.keySet().stream().sorted().toList(), adapter.enabled(), adapter.createdAt(), adapter.updatedAt());
        } catch (Exception exception) {
            throw new IllegalStateException("企业适配器配置无法读取", exception);
        }
    }

    private PluginManifest parseAndValidateManifest(String raw) {
        try {
            PluginManifest value = json.readValue(raw, PluginManifest.class);
            String key = normalizePluginKey(value.pluginKey());
            String name = required(value.displayName(), 120, "插件名称");
            String type = value.pluginType() == null ? "" : value.pluginType().trim().toUpperCase(Locale.ROOT);
            if (!PLUGIN_TYPES.contains(type)) throw rejected("不支持的插件类型");
            String version = normalizeVersion(value.version());
            String entrypoint = required(value.entrypoint(), 180, "插件入口");
            if (!entrypoint.matches("[a-zA-Z0-9._/-]{1,180}")) throw rejected("插件入口格式不合法");
            List<String> permissions = value.permissions() == null ? List.of() : value.permissions().stream()
                    .map(permission -> permission == null ? "" : permission.trim().toLowerCase(Locale.ROOT)).distinct().sorted().toList();
            if (permissions.isEmpty() || !PLUGIN_PERMISSIONS.containsAll(permissions)) throw rejected("插件声明了不受支持的权限");
            return new PluginManifest(key, name, type, version, entrypoint, permissions);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("插件清单必须是符合契约的 JSON");
        }
    }

    private int compareVersions(String next, String current) {
        List<Integer> left = versionParts(next);
        List<Integer> right = versionParts(current);
        for (int index = 0; index < left.size(); index++) {
            int comparison = Integer.compare(left.get(index), right.get(index));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private List<Integer> versionParts(String value) {
        return java.util.Arrays.stream(value.split("\\.")).map(Integer::parseInt).toList();
    }

    private String normalizeVersion(String value) {
        String normalized = required(value, 48, "插件版本");
        if (!normalized.matches("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)")) throw rejected("插件版本必须为 MAJOR.MINOR.PATCH");
        return normalized;
    }

    private String normalizePluginKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9.-]{2,79}")) throw rejected("插件标识格式不合法");
        return normalized;
    }

    private String normalizeKeyId(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]{2,79}")) throw rejected("签名密钥标识格式不合法");
        return normalized;
    }

    private String required(String value, int maximum, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximum) throw rejected(field + "不合法");
        return normalized;
    }

    private void verifyPublicKey(String encoded) {
        try {
            KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        } catch (Exception exception) {
            throw rejected("签名公钥必须是 Ed25519 X.509 Base64 编码");
        }
    }

    private void verifySignature(String publicKeyBase64, String manifest, String signatureBase64) {
        try {
            PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(manifest.getBytes(StandardCharsets.UTF_8));
            if (!signature.verify(Base64.getDecoder().decode(signatureBase64))) throw rejected("插件签名校验失败");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw rejected("插件签名格式不合法");
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法校验插件包", exception);
        }
    }

    private String write(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密适配器配置", exception);
        }
    }

    private void requireSignedIn(AuthenticatedPrincipal principal) {
        if (principal == null) throw new org.springframework.security.access.AccessDeniedException("需要登录");
    }

    private ResponseStatusException rejected(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }

    public record SigningKeyInput(String keyId, String displayName, String publicKeyBase64) { }
    public record PackageInput(String signerKeyId, String manifestJson, String signatureBase64) { }
    public record AdapterInput(String adapterType, String displayName, String endpoint, Map<String, String> configuration,
                               boolean enabled) { }
}
