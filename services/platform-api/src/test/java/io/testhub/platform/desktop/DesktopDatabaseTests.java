package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class DesktopDatabaseTests {
    @Test
    void storesJsonDocumentsWithoutDoubleEncodingAndQueriesTheirShape() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:json-compat;MODE=MySQL", "sa", "")) {
            JdbcClient raw = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            JdbcClient local = sql -> raw.sql(DesktopSql.translate(sql));
            raw.sql("CREATE ALIAS JSON_LENGTH FOR 'io.testhub.platform.desktop.DesktopJsonFunctions.length'").update();
            raw.sql("CREATE ALIAS JSON_SEARCH FOR 'io.testhub.platform.desktop.DesktopJsonFunctions.search'").update();
            raw.sql("CREATE TABLE asset (id INT PRIMARY KEY, steps JSON)").update();
            String document = "[{\"pageObjectId\":\"a_b\"},{\"pageObjectId\":\"other\"}]";
            local.sql("INSERT INTO asset VALUES (1, CAST(:steps AS JSON))").param("steps", document).update();
            assertThat(local.sql("SELECT steps FROM asset").query(String.class).single()).isEqualTo(document);
            assertThat(local.sql("SELECT JSON_LENGTH(steps) FROM asset").query(Integer.class).single()).isEqualTo(2);
            assertThat(local.sql("SELECT JSON_SEARCH(steps,'one',:id,NULL,'$[*].pageObjectId') FROM asset")
                    .param("id", "a\\_b").query(String.class).single()).isEqualTo("\"$[0].pageObjectId\"");
            assertThat(local.sql("SELECT COUNT(*) FROM asset WHERE JSON_SEARCH(steps,'one',:id,NULL,'$[*].pageObjectId') IS NOT NULL")
                    .param("id", "missing").query(Integer.class).single()).isZero();
            assertThatThrownBy(() -> local.sql("INSERT INTO asset VALUES (2, CAST(:steps AS JSON))")
                    .param("steps", "not-json").update()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    @Test
    void translatesTimestampPrecisionAndPreservesSqlLiterals() {
        assertThat(DesktopSql.translate("SELECT DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 121 SECOND)"))
                .isEqualTo("SELECT DATEADD('SECOND', -121, CURRENT_TIMESTAMP(6))");
        assertThat(DesktopSql.translate("SELECT 'CAST(:text AS JSON)', CAST(:body AS JSON), 'it''s DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND)'"))
                .isEqualTo("SELECT 'CAST(:text AS JSON)', :body FORMAT JSON, 'it''s DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 5 SECOND)'");
    }
}
