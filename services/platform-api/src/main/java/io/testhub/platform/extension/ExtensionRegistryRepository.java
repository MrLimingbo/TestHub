package io.testhub.platform.extension;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ExtensionRegistryRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ExtensionRegistryRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<PluginSigningKey> findSigningKeys() {
        return jdbc.sql("SELECT id, key_id, display_name, enabled, created_at, revoked_at FROM plugin_signing_key ORDER BY created_at DESC")
                .query((rs, row) -> key(rs)).list();
    }

    public Optional<StoredSigningKey> findEnabledSigningKey(String keyId) {
        return jdbc.sql("SELECT id, key_id, display_name, public_key_base64, enabled, created_at, revoked_at FROM plugin_signing_key "
                        + "WHERE key_id=:keyId AND enabled=TRUE")
                .param("keyId", keyId).query((rs, row) -> new StoredSigningKey(key(rs), rs.getString("public_key_base64"))).optional();
    }

    public void insertSigningKey(String id, String keyId, String displayName, String publicKeyBase64, String createdBy) {
        jdbc.sql("""
                INSERT INTO plugin_signing_key (id, key_id, display_name, public_key_base64, enabled, created_by)
                VALUES (:id, :keyId, :displayName, :publicKey, TRUE, :createdBy)
                """).param("id", id).param("keyId", keyId).param("displayName", displayName).param("publicKey", publicKeyBase64)
                .param("createdBy", createdBy).update();
    }

    public void setSigningKeyEnabled(String id, boolean enabled) {
        jdbc.sql("UPDATE plugin_signing_key SET enabled=:enabled, revoked_at=CASE WHEN :enabled THEN NULL ELSE CURRENT_TIMESTAMP(3) END WHERE id=:id")
                .param("id", id).param("enabled", enabled).update();
    }

    public List<InstalledPlugin> findPlugins() {
        return jdbc.sql(pluginSelect() + " ORDER BY updated_at DESC, plugin_key").query((rs, row) -> plugin(rs)).list();
    }

    public Optional<InstalledPlugin> findPluginByKey(String pluginKey) {
        return jdbc.sql(pluginSelect() + " WHERE plugin_key=:pluginKey").param("pluginKey", pluginKey)
                .query((rs, row) -> plugin(rs)).optional();
    }

    public Optional<InstalledPlugin> findPlugin(String id) {
        return jdbc.sql(pluginSelect() + " WHERE id=:id").param("id", id).query((rs, row) -> plugin(rs)).optional();
    }

    public void insertPlugin(String id, PluginManifest manifest, String signerId, String checksum, String manifestJson,
                             String installedBy) {
        jdbc.sql("""
                INSERT INTO platform_plugin (id, plugin_key, display_name, plugin_type, active_version, status, signer_key_id,
                    package_checksum, manifest_json, permissions, entrypoint, installed_by, updated_by)
                VALUES (:id, :pluginKey, :displayName, :pluginType, :version, 'DISABLED', :signerId, :checksum,
                    CAST(:manifestJson AS JSON), CAST(:permissions AS JSON), :entrypoint, :installedBy, :installedBy)
                """).param("id", id).param("pluginKey", manifest.pluginKey()).param("displayName", manifest.displayName())
                .param("pluginType", manifest.pluginType()).param("version", manifest.version()).param("signerId", signerId)
                .param("checksum", checksum).param("manifestJson", manifestJson).param("permissions", write(manifest.permissions()))
                .param("entrypoint", manifest.entrypoint()).param("installedBy", installedBy).update();
    }

    public void updatePluginRelease(String id, PluginManifest manifest, String signerId, String checksum, String manifestJson,
                                    String updatedBy) {
        jdbc.sql("""
                UPDATE platform_plugin
                SET display_name=:displayName, plugin_type=:pluginType, active_version=:version, status='DISABLED',
                    signer_key_id=:signerId, package_checksum=:checksum, manifest_json=CAST(:manifestJson AS JSON),
                    permissions=CAST(:permissions AS JSON), entrypoint=:entrypoint, updated_by=:updatedBy,
                    disabled_at=CURRENT_TIMESTAMP(3), enabled_at=NULL, updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=:id
                """).param("id", id).param("displayName", manifest.displayName()).param("pluginType", manifest.pluginType())
                .param("version", manifest.version()).param("signerId", signerId).param("checksum", checksum)
                .param("manifestJson", manifestJson).param("permissions", write(manifest.permissions())).param("entrypoint", manifest.entrypoint())
                .param("updatedBy", updatedBy).update();
    }

    public void insertRelease(String id, String pluginId, String version, String signerId, String checksum, String manifestJson,
                              String signatureBase64, String installedBy) {
        jdbc.sql("""
                INSERT INTO platform_plugin_release (id, plugin_id, version, signer_key_id, package_checksum, manifest_json,
                    signature_base64, installed_by)
                VALUES (:id, :pluginId, :version, :signerId, :checksum, CAST(:manifestJson AS JSON), :signature, :installedBy)
                """).param("id", id).param("pluginId", pluginId).param("version", version).param("signerId", signerId)
                .param("checksum", checksum).param("manifestJson", manifestJson).param("signature", signatureBase64)
                .param("installedBy", installedBy).update();
    }

    public void setPluginStatus(String id, String status, String updatedBy) {
        jdbc.sql("""
                UPDATE platform_plugin
                SET status=:status, updated_by=:updatedBy, enabled_at=CASE WHEN :status='ENABLED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                    disabled_at=CASE WHEN :status='DISABLED' THEN CURRENT_TIMESTAMP(3) ELSE NULL END, updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=:id
                """).param("id", id).param("status", status).param("updatedBy", updatedBy).update();
    }

    public List<StoredAdapter> findAdapters() {
        return jdbc.sql("""
                SELECT id, adapter_type, display_name, endpoint, configuration_ciphertext, enabled, created_at, updated_at
                FROM enterprise_adapter_configuration ORDER BY adapter_type, display_name
                """).query((rs, row) -> new StoredAdapter(rs.getString("id"), rs.getString("adapter_type"),
                rs.getString("display_name"), rs.getString("endpoint"), rs.getString("configuration_ciphertext"),
                rs.getBoolean("enabled"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).list();
    }

    public Optional<StoredAdapter> findAdapter(String id) { return findAdapter(id, false); }

    public Optional<StoredAdapter> findAdapterForUpdate(String id) { return findAdapter(id, true); }

    private Optional<StoredAdapter> findAdapter(String id, boolean lock) {
        return jdbc.sql("""
                SELECT id, adapter_type, display_name, endpoint, configuration_ciphertext, enabled, created_at, updated_at
                FROM enterprise_adapter_configuration WHERE id=:id
                """ + (lock ? " FOR UPDATE" : "")).param("id", id).query((rs, row) -> new StoredAdapter(rs.getString("id"), rs.getString("adapter_type"),
                rs.getString("display_name"), rs.getString("endpoint"), rs.getString("configuration_ciphertext"),
                rs.getBoolean("enabled"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")))).optional();
    }

    public void insertAdapter(String id, String type, String displayName, String endpoint, String ciphertext, boolean enabled,
                              String createdBy) {
        jdbc.sql("""
                INSERT INTO enterprise_adapter_configuration (id, adapter_type, display_name, endpoint, configuration_ciphertext,
                    enabled, created_by, updated_by)
                VALUES (:id, :type, :displayName, :endpoint, :ciphertext, :enabled, :createdBy, :createdBy)
                """).param("id", id).param("type", type).param("displayName", displayName).param("endpoint", endpoint)
                .param("ciphertext", ciphertext).param("enabled", enabled).param("createdBy", createdBy).update();
    }

    public void updateAdapter(String id, String type, String displayName, String endpoint, String ciphertext, boolean enabled,
                              String updatedBy) {
        jdbc.sql("""
                UPDATE enterprise_adapter_configuration
                SET adapter_type=:type, display_name=:displayName, endpoint=:endpoint, configuration_ciphertext=:ciphertext,
                    enabled=:enabled, updated_by=:updatedBy, updated_at=CURRENT_TIMESTAMP(3)
                WHERE id=:id
                """).param("id", id).param("type", type).param("displayName", displayName).param("endpoint", endpoint)
                .param("ciphertext", ciphertext).param("enabled", enabled).param("updatedBy", updatedBy).update();
    }

    public void deleteAdapter(String id) {
        jdbc.sql("DELETE FROM enterprise_adapter_configuration WHERE id=:id").param("id", id).update();
    }

    private String pluginSelect() {
        return "SELECT id, plugin_key, display_name, plugin_type, active_version, status, signer_key_id, package_checksum, "
                + "permissions, entrypoint, installed_at, updated_at, enabled_at, disabled_at FROM platform_plugin";
    }

    private PluginSigningKey key(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PluginSigningKey(rs.getString("id"), rs.getString("key_id"), rs.getString("display_name"),
                rs.getBoolean("enabled"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("revoked_at")));
    }

    private InstalledPlugin plugin(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InstalledPlugin(rs.getString("id"), rs.getString("plugin_key"), rs.getString("display_name"),
                rs.getString("plugin_type"), rs.getString("active_version"), rs.getString("status"), rs.getString("signer_key_id"),
                rs.getString("package_checksum"), readStringList(rs.getString("permissions")), rs.getString("entrypoint"),
                instant(rs.getTimestamp("installed_at")), instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("enabled_at")),
                instant(rs.getTimestamp("disabled_at")));
    }

    private List<String> readStringList(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取插件权限", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化扩展配置", exception);
        }
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record StoredSigningKey(PluginSigningKey key, String publicKeyBase64) { }
    public record StoredAdapter(String id, String adapterType, String displayName, String endpoint,
                                String configurationCiphertext, boolean enabled, Instant createdAt, Instant updatedAt) { }
}
