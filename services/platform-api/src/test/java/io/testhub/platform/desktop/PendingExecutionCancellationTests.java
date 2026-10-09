package io.testhub.platform.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testhub.platform.shared.persistence.PendingExecutionCancellation;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class PendingExecutionCancellationTests {
    @Test
    void cancellationLocksOutboxUntilCommitAndPreventsDispatchClaim() throws Exception {
        String url = "jdbc:h2:mem:cancel-" + UUID.randomUUID();
        try (var keeper = DriverManager.getConnection(url, "sa", ""); var workers = Executors.newFixedThreadPool(2)) {
            var source = new DriverManagerDataSource(url, "sa", "");
            JdbcClient jdbc = JdbcClient.create(source);
            initialize(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            var cancellation = new PendingExecutionCancellation(jdbc);
            var changed = new CountDownLatch(1);
            var commit = new CountDownLatch(1);
            var cancel = workers.submit(() -> transaction.execute(status -> {
                int result = cancellation.cancel(PendingExecutionCancellation.Engine.API, "project", "execution");
                changed.countDown();
                await(commit);
                return result;
            }));
            try {
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                var claiming = new CountDownLatch(1);
                var claim = workers.submit(() -> {
                    claiming.countDown();
                    return jdbc.sql("UPDATE api_runner_outbox SET status='DISPATCHING' "
                            + "WHERE execution_id='execution' AND status='PENDING'").update();
                });
                assertThat(claiming.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> claim.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                commit.countDown();
                assertThat(cancel.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(claim.get(5, TimeUnit.SECONDS)).isZero();
                assertThat(jdbc.sql("SELECT status FROM api_automation_execution").query(String.class).single()).isEqualTo("CANCELLED");
                assertThat(jdbc.sql("SELECT status FROM api_runner_outbox").query(String.class).single()).isEqualTo("CANCELLED");
            } finally { commit.countDown(); }
        }
    }

    @Test
    void rollbackRestoresBothRowsAndWrongScopeCannotCancel() throws Exception {
        String url = "jdbc:h2:mem:rollback-" + UUID.randomUUID();
        try (var keeper = DriverManager.getConnection(url, "sa", "")) {
            var source = new DriverManagerDataSource(url, "sa", "");
            JdbcClient jdbc = JdbcClient.create(source);
            initialize(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            var cancellation = new PendingExecutionCancellation(jdbc);
            assertThatThrownBy(() -> transaction.execute(status -> {
                cancellation.cancel(PendingExecutionCancellation.Engine.API, "project", "execution");
                throw new IllegalStateException("simulated transaction failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.sql("SELECT status FROM api_automation_execution").query(String.class).single()).isEqualTo("QUEUED");
            assertThat(jdbc.sql("SELECT status FROM api_runner_outbox").query(String.class).single()).isEqualTo("PENDING");
            assertThat(transaction.<Integer>execute(status -> cancellation.cancel(PendingExecutionCancellation.Engine.API,
                    "another-project", "execution"))).isEqualTo(0);
            jdbc.sql("UPDATE api_runner_outbox SET status='DISPATCHING'").update();
            assertThat(transaction.<Integer>execute(status -> cancellation.cancel(PendingExecutionCancellation.Engine.API,
                    "project", "execution"))).isEqualTo(0);
        }
    }

    private static void initialize(JdbcClient jdbc) {
        jdbc.sql("CREATE TABLE api_automation_execution(id VARCHAR PRIMARY KEY, project_id VARCHAR, status VARCHAR, "
                + "status_code VARCHAR, status_message VARCHAR, finished_at TIMESTAMP, updated_at TIMESTAMP)").update();
        jdbc.sql("CREATE TABLE api_runner_outbox(id VARCHAR PRIMARY KEY, execution_id VARCHAR, message_type VARCHAR, "
                + "status VARCHAR, locked_at TIMESTAMP, last_error VARCHAR, updated_at TIMESTAMP)").update();
        jdbc.sql("INSERT INTO api_automation_execution(id,project_id,status) VALUES('execution','project','QUEUED')").update();
        jdbc.sql("INSERT INTO api_runner_outbox(id,execution_id,message_type,status) VALUES('outbox','execution','TASK','PENDING')").update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for transaction test");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
