package io.testhub.platform.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static io.testhub.platform.model.ModelProviderController.*;

@Repository
class ModelProviderRepository {
    private static final String SELECT = """
            SELECT id, display_name, api_base_url, api_protocol, retry_policy, api_key_ciphertext,
                   model_catalog, version
              FROM model_provider
            """;
    private static final TypeReference<List<Model>> CATALOG_TYPE = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    ModelProviderRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Provider> list() {
        return jdbc.sql(SELECT + " ORDER BY display_name, id").query(this::read)
                .list().stream().map(StoredProvider::provider).toList();
    }

    Optional<StoredProvider> find(String id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(this::read).optional();
    }

    void insert(Provider provider, String ciphertext) {
        bind("""
                INSERT INTO model_provider (id, display_name, api_base_url, api_protocol, retry_policy,
                                            api_key_ciphertext, model_catalog, version)
                VALUES (:id, :name, :url, :protocol, :retry, :ciphertext, :catalog, :version)
                """, provider, ciphertext).update();
    }

    boolean update(Provider provider, String ciphertext, boolean replaceKey) {
        return bind("""
                UPDATE model_provider
                   SET display_name = :name, api_base_url = :url, api_protocol = :protocol,
                       retry_policy = :retry, model_catalog = :catalog, version = :version,
                       api_key_ciphertext = CASE WHEN :replaceKey THEN :ciphertext ELSE api_key_ciphertext END
                 WHERE id = :id AND version = :previousVersion
                """, provider, ciphertext)
                .param("replaceKey", replaceKey).param("previousVersion", provider.version() - 1).update() == 1;
    }

    boolean delete(String id) {
        return jdbc.sql("DELETE FROM model_provider WHERE id = :id").param("id", id).update() == 1;
    }

    private JdbcClient.StatementSpec bind(String sql, Provider provider, String ciphertext) {
        try {
            return jdbc.sql(sql)
                    .param("id", provider.id()).param("name", provider.displayName())
                    .param("url", provider.apiBaseUrl()).param("protocol", provider.protocol().name())
                    .param("retry", provider.retryPolicy().name()).param("ciphertext", ciphertext, Types.VARCHAR)
                    .param("catalog", json.writeValueAsString(provider.models())).param("version", provider.version());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize the model catalog", exception);
        }
    }

    private StoredProvider read(ResultSet row, int index) throws SQLException {
        String ciphertext = row.getString("api_key_ciphertext");
        try {
            Provider provider = new Provider(row.getString("id"), row.getString("display_name"),
                    row.getString("api_base_url"), Protocol.valueOf(row.getString("api_protocol")),
                    RetryPolicy.valueOf(row.getString("retry_policy")), ciphertext != null,
                    json.readValue(row.getString("model_catalog"), CATALOG_TYPE), row.getLong("version"));
            return new StoredProvider(provider, ciphertext);
        } catch (JsonProcessingException exception) {
            throw new SQLException("Cannot read the model catalog", exception);
        }
    }

    record StoredProvider(Provider provider, String ciphertext) { }
}
