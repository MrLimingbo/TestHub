package io.testhub.platform.shared.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Atomically cancels an execution and its still-pending outbox without a vendor-specific multi-table UPDATE. */
@Component
public class PendingExecutionCancellation {
    private final JdbcClient jdbc;

    public PendingExecutionCancellation(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional
    public int cancel(Engine engine, String projectId, String executionId) {
        // Lock both joined rows before mutation, so the dispatcher cannot claim the pending message in between.
        var pending = jdbc.sql("SELECT execution.id FROM " + engine.executionTable + " execution JOIN "
                + engine.outboxTable + " outbox ON outbox.execution_id = execution.id "
                + "WHERE execution.project_id = :project AND execution.id = :execution AND execution.status = 'QUEUED' "
                + "AND outbox.message_type = 'TASK' AND outbox.status = 'PENDING' FOR UPDATE")
                .param("project", projectId).param("execution", executionId).query(String.class).list();
        if (pending.isEmpty()) return 0;
        int changed = jdbc.sql("UPDATE " + engine.executionTable + " SET status = 'CANCELLED', "
                + "status_code = 'CANCELLED_BEFORE_DISPATCH', status_message = :message, "
                + "finished_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
                + "WHERE project_id = :project AND id = :execution AND status = 'QUEUED'")
                .param("message", engine.message).param("project", projectId).param("execution", executionId).update();
        if (changed > 0) {
            jdbc.sql("UPDATE " + engine.outboxTable + " SET status = 'CANCELLED', locked_at = NULL, "
                    + "last_error = NULL, updated_at = CURRENT_TIMESTAMP(3) "
                    + "WHERE execution_id = :execution AND message_type = 'TASK' AND status = 'PENDING'")
                    .param("execution", executionId).update();
        }
        return changed;
    }

    // Identifiers come exclusively from this closed enum, never request input.
    public enum Engine {
        API("api_automation_execution", "api_runner_outbox", "任务在投递给 Runner 前已取消。"),
        PERFORMANCE("performance_test_execution", "performance_runner_outbox", "任务在投递给 JMeter Runner 前已取消。"),
        UI("ui_automation_execution", "ui_automation_runner_outbox", "任务在投递给 Runner 前已取消。");

        private final String executionTable;
        private final String outboxTable;
        private final String message;
        Engine(String executionTable, String outboxTable, String message) {
            this.executionTable = executionTable;
            this.outboxTable = outboxTable;
            this.message = message;
        }
    }
}
