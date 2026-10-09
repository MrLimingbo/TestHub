package io.testhub.platform.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** Offline database inspection and restore preparation, in a separate bundled JVM. */
public final class DesktopBackupTool {
    private DesktopBackupTool() {}

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !("inspect".equals(args[0]) || "prepare-restore".equals(args[0])))
                throw new IllegalArgumentException("Invalid backup operation");
            var result = prepare(Path.of(args[1]), System.getenv("TESTHUB_LOCAL_DB_PASSWORD"), "prepare-restore".equals(args[0]));
            System.out.println("TESTHUB_BACKUP_RESULT=" + new ObjectMapper().writeValueAsString(result));
        } catch (Exception error) {
            System.err.println("工作区数据库校验失败；请确认备份完整、数据库未被其他进程使用，且 TestHub 版本兼容。");
            System.exit(2);
        }
    }

    static Map<String, Object> prepare(Path directory, String password, boolean restore) throws Exception {
        if (!directory.isAbsolute()) throw new IllegalArgumentException("Invalid workspace path");
        directory = directory.toRealPath();
        if (password == null || password.isBlank() || directory.toString().contains(";") || directory.toString().chars().anyMatch(Character::isISOControl)
                || !Files.isRegularFile(directory.resolve("workspace.mv.db"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Invalid workspace database");
        String url = "jdbc:h2:file:" + directory.resolve("workspace") + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        var flyway = Flyway.configure().dataSource(url, "sa", password).locations("classpath:db/desktop").ignoreMigrationPatterns("*:pending").load();
        var validation = flyway.validateWithResult();
        if (!validation.validationSuccessful || flyway.info().current() == null) throw new IllegalStateException("Database migrations are incompatible");
        var result = new LinkedHashMap<String, Object>();
        result.put("databaseVersion", flyway.info().current().getVersion().toString());
        // Migrate only the isolated restore candidate, never the source workspace during export.
        if (restore) flyway.migrate();
        try (var connection = DriverManager.getConnection(url, "sa", password)) {
            result.put("engineVersion", connection.getMetaData().getDatabaseProductVersion());
            if (!restore) return result;
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                for (String table : new String[]{"desktop_session_attributes", "desktop_session", "desktop_runtime_state",
                        "desktop_stream_pending", "desktop_stream_group", "desktop_stream_message", "desktop_stream", "desktop_runner_heartbeat",
                        "api_runner_outbox", "performance_runner_outbox", "ui_automation_runner_outbox", "mobile_automation_runner_outbox"})
                    statement.executeUpdate("DELETE FROM " + table);
                int disabled = 0, cancelled = 0;
                for (String table : new String[]{"api_automation_schedule", "ui_automation_schedule", "quality_report_schedule", "api_automation_webhook_trigger"})
                    disabled += statement.executeUpdate("UPDATE " + table + " SET enabled=FALSE WHERE enabled=TRUE");
                for (String table : new String[]{"api_automation_execution", "performance_test_execution", "ui_automation_execution", "mobile_automation_execution"})
                    cancelled += statement.executeUpdate("UPDATE " + table + " SET status='CANCELLED',status_code='WORKSPACE_RESTORED',"
                            + "status_message='工作区从备份恢复，未重放原执行任务。',finished_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING')");
                statement.executeUpdate("UPDATE execution_task SET status='CANCELLED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE status IN ('QUEUED','ACCEPTED','RUNNING','CANCELLING')");
                statement.executeUpdate("UPDATE notification_delivery SET status='FAILED',last_error='工作区从备份恢复，未重放通知。',updated_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RETRYING','DELIVERING')");
                statement.executeUpdate("UPDATE quality_report_schedule_run SET status='FAILED',error_message='工作区从备份恢复，未重放报告任务。',finished_at=CURRENT_TIMESTAMP WHERE status IN ('PENDING','RUNNING')");
                connection.commit();
                result.put("disabledSchedulesAndWebhooks", disabled); result.put("cancelledExecutions", cancelled);
            } catch (Exception error) { connection.rollback(); throw error; }
        }
        return result;
    }
}
