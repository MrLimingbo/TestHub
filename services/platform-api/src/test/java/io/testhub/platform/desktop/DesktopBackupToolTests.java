package io.testhub.platform.desktop;

import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesktopBackupToolTests {
    @TempDir Path directory;
    String url() { return "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE"; }
    void initialize() { Flyway.configure().dataSource(url(), "sa", "backup-fixture").locations("classpath:db/desktop").load().migrate(); }
    @Test void inspectionPreservesStateAndRestoreClearsEphemeralData() throws Exception {
        initialize();
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO desktop_runtime_state(state_key,state_value,expires_at) VALUES('nonce','value',999999)");
            sql.executeUpdate("INSERT INTO desktop_stream(stream_key,last_id) VALUES('queue',0)");
        }
        assertEquals("50", DesktopBackupTool.prepare(directory, "backup-fixture", false).get("databaseVersion"));
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement(); var row = sql.executeQuery("SELECT COUNT(*) FROM desktop_runtime_state")) { row.next(); assertEquals(1, row.getInt(1)); }
        DesktopBackupTool.prepare(directory, "backup-fixture", true);
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement()) {
            for (String table : new String[]{"desktop_runtime_state", "desktop_stream"}) try (var row = sql.executeQuery("SELECT COUNT(*) FROM " + table)) { row.next(); assertEquals(0, row.getInt(1)); }
        }
    }
    @Test void unknownFutureMigrationAndWrongPasswordFailBeforeRestore() throws Exception {
        initialize();
        assertThrows(Exception.class, () -> DesktopBackupTool.prepare(directory, "wrong-password", true));
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES(99,'99','future','SQL','V99__future.sql',1,'sa',0,TRUE)");
        }
        assertThrows(Exception.class, () -> DesktopBackupTool.prepare(directory, "backup-fixture", true));
    }
    @Test void olderCandidateMigratesWithoutLosingBusinessSecrets() throws Exception {
        Flyway.configure().dataSource(url(), "sa", "backup-fixture").locations("classpath:db/desktop").target("3").load().migrate();
        var cipher = new io.testhub.platform.security.SecretCipher(java.util.Base64.getEncoder().encodeToString(new byte[32]), "primary", "");
        var encrypted = cipher.encrypt("fixture-portable-secret");
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var insert = connection.prepareStatement(
                "INSERT INTO system_config(config_key,config_value,secret,updated_by) VALUES('backup.fixture',?,TRUE,'fixture')")) {
            insert.setString(1, encrypted); insert.executeUpdate();
        }
        assertEquals("3", DesktopBackupTool.prepare(directory, "backup-fixture", false).get("databaseVersion"));
        DesktopBackupTool.prepare(directory, "backup-fixture", true);
        assertEquals("50", DesktopBackupTool.prepare(directory, "backup-fixture", false).get("databaseVersion"));
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement();
             var row = sql.executeQuery("SELECT config_value FROM system_config WHERE config_key='backup.fixture'")) {
            assertTrue(row.next()); assertEquals(encrypted, row.getString(1));
            assertEquals("fixture-portable-secret", cipher.decrypt(row.getString(1)));
        }
    }
    @Test void restoreDisablesTriggersAndCancelsPendingExecutionWithoutDeletingHistory() throws Exception {
        initialize();
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO user_account(id,account,email,display_name,password_hash) VALUES('u','backup-fixture','backup@example.test','fixture','unused')");
            sql.executeUpdate("INSERT INTO organization(id,name,code) VALUES('o','fixture','fixture')");
            sql.executeUpdate("INSERT INTO project(id,organization_id,name,code) VALUES('p','o','fixture','fixture')");
            sql.executeUpdate("INSERT INTO api_automation_scenario(id,organization_id,project_id,name,steps,variables,created_by) VALUES('s','o','p','fixture','[]','{}','u')");
            sql.executeUpdate("INSERT INTO api_automation_webhook_trigger(id,organization_id,project_id,scenario_id,name,secret_ciphertext,created_by) VALUES('w','o','p','s','fixture','fixture-encrypted','u')");
            sql.executeUpdate("INSERT INTO api_automation_schedule(id,organization_id,project_id,scenario_id,name,cron_expression,created_by) VALUES('c','o','p','s','fixture','0 0 0 1 1 ? 2099','u')");
            for (String status : new String[]{"RUNNING", "SUCCEEDED"}) sql.executeUpdate("INSERT INTO api_automation_execution(id,task_id,organization_id,project_id,scenario_id,scenario_name,scenario_version,status,manifest_ref,requested_by,requested_at,deadline_at) VALUES('" + status + "','" + status + "','o','p','s','fixture',1,'" + status + "','fixture','u',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO api_runner_outbox(id,execution_id,stream_key,payload) VALUES('outbox','RUNNING','queue','{}')");
        }
        var result = DesktopBackupTool.prepare(directory, "backup-fixture", true);
        assertEquals(2, result.get("disabledSchedulesAndWebhooks"));
        assertEquals(1, result.get("cancelledExecutions"));
        try (var connection = DriverManager.getConnection(url(), "sa", "backup-fixture"); var sql = connection.createStatement()) {
            try (var row = sql.executeQuery("SELECT status,status_code FROM api_automation_execution WHERE id='RUNNING'")) { assertTrue(row.next()); assertEquals("CANCELLED", row.getString(1)); assertEquals("WORKSPACE_RESTORED", row.getString(2)); }
            try (var row = sql.executeQuery("SELECT status FROM api_automation_execution WHERE id='SUCCEEDED'")) { assertTrue(row.next()); assertEquals("SUCCEEDED", row.getString(1)); }
            try (var row = sql.executeQuery("SELECT COUNT(*) FROM api_runner_outbox")) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
        }
    }
}
