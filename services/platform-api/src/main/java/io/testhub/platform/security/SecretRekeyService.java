package io.testhub.platform.security;

import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Re-encrypts persisted secret columns only after a staged key-ring deployment and explicit operator confirmation. */
@Service
public class SecretRekeyService {
    public static final String CONFIRMATION = "REKEY_PERSISTED_SECRETS";
    private final AccessService access;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final SecretCipher cipher;
    private final boolean enabled;

    public SecretRekeyService(AccessService access, AuditService audit, JdbcClient jdbc, SecretCipher cipher,
                              @Value("${testhub.security.secret-rekey.enabled:false}") boolean enabled) {
        this.access = access;
        this.audit = audit;
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    @Transactional
    public RekeySummary rekey(AuthenticatedPrincipal principal, String confirmation) {
        access.requireSystemAdmin(principal);
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "密钥重加密窗口未启用");
        if (!CONFIRMATION.equals(confirmation)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密钥重加密需要明确确认");
        }
        if (cipher.fallbackKeyCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "密钥重加密需要至少一个旧密钥回退配置");
        }
        Map<String, Integer> updated = new LinkedHashMap<>();
        updated.put("project_parameter", rekeyColumn("project_parameter", "parameter_value", "secret = TRUE"));
        updated.put("api_automation_webhook_trigger", rekeyColumn("api_automation_webhook_trigger", "secret_ciphertext", "TRUE"));
        updated.put("notification_template", rekeyColumn("notification_template", "signing_secret_ciphertext",
                "signing_secret_ciphertext IS NOT NULL AND signing_secret_ciphertext <> ''"));
        updated.put("notification_delivery", rekeyColumn("notification_delivery", "signing_secret_ciphertext", "TRUE"));
        updated.put("enterprise_adapter_configuration", rekeyColumn("enterprise_adapter_configuration", "configuration_ciphertext", "TRUE"));
        int total = updated.values().stream().mapToInt(Integer::intValue).sum();
        RekeySummary summary = new RekeySummary(cipher.activeKeyId(), total, Map.copyOf(updated));
        audit.record(principal, null, null, "PERSISTED_SECRETS_REKEYED", "PLATFORM_SECURITY", cipher.activeKeyId(),
                Map.of("updatedCount", total, "sources", summary.updatedBySource()));
        return summary;
    }

    private int rekeyColumn(String table, String column, String predicate) {
        List<EncryptedRow> rows = jdbc.sql("SELECT id, " + column + " AS ciphertext FROM " + table + " WHERE " + predicate)
                .query((rs, rowNumber) -> new EncryptedRow(rs.getString("id"), rs.getString("ciphertext"))).list();
        int updated = 0;
        for (EncryptedRow row : rows) {
            if (cipher.isEncryptedWithActiveKey(row.ciphertext())) continue;
            String replacement = cipher.encrypt(cipher.decrypt(row.ciphertext()));
            updated += jdbc.sql("UPDATE " + table + " SET " + column + " = :ciphertext WHERE id = :id")
                    .param("ciphertext", replacement).param("id", row.id()).update();
        }
        return updated;
    }

    private record EncryptedRow(String id, String ciphertext) { }

    public record RekeySummary(String activeKeyId, int updatedCount, Map<String, Integer> updatedBySource) { }
}
