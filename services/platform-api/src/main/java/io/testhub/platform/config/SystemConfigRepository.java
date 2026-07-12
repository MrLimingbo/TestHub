package io.testhub.platform.config;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SystemConfigRepository {
    private final JdbcClient jdbc;

    public SystemConfigRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SystemConfig> findAllNonSecret() {
        return jdbc.sql("""
                SELECT config_key, config_value, secret, updated_by, updated_at
                FROM system_config WHERE secret = FALSE ORDER BY config_key
                """).query((rs, rowNumber) -> new SystemConfig(rs.getString("config_key"), rs.getString("config_value"),
                rs.getBoolean("secret"), rs.getString("updated_by"), instant(rs.getTimestamp("updated_at")))).list();
    }

    public void upsert(String key, String value, String updatedBy) {
        jdbc.sql("""
                INSERT INTO system_config (config_key, config_value, secret, updated_by)
                VALUES (:key, :value, FALSE, :updatedBy)
                ON DUPLICATE KEY UPDATE config_value = VALUES(config_value), secret = FALSE,
                    updated_by = VALUES(updated_by), updated_at = CURRENT_TIMESTAMP(3)
                """).param("key", key).param("value", value).param("updatedBy", updatedBy).update();
    }

    public void deleteNonSecret(String key) {
        jdbc.sql("DELETE FROM system_config WHERE config_key = :key AND secret = FALSE").param("key", key).update();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
