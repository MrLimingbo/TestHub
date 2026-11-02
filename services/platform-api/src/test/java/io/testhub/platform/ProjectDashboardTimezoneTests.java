package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.testhub.platform.dashboard.ProjectDashboardRepository;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class ProjectDashboardTimezoneTests {
    @ParameterizedTest
    @CsvSource({"UTC,7", "UTC,30", "UTC,90", "Asia/Shanghai,7", "Asia/Shanghai,30", "Asia/Shanghai,90",
            "America/New_York,7", "America/New_York,30", "America/New_York,90"})
    void groupsEverySourceByShanghaiBoundariesRegardlessOfJvmTimezone(String jvmZone, int days) throws Exception {
        // Fork instead of mutating the suite JVM: JDBC drivers can cache their first default timezone.
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "-Duser.timezone=" + jvmZone, "-cp", classpath,
                getClass().getName(), String.valueOf(days)).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Timezone probe did not finish");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    public static void main(String[] args) {
        verifyBoundaries(Integer.parseInt(args[0]));
    }

    private static void verifyBoundaries(int days) {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", true);
        try {
            JdbcClient jdbc = JdbcClient.create(dataSource);
            jdbc.sql("CREATE TABLE functional_test_run (id VARCHAR, project_id VARCHAR)").update();
            jdbc.sql("CREATE TABLE functional_test_run_case (run_id VARCHAR, status VARCHAR, executed_at TIMESTAMP(3))").update();
            for (String table : List.of("api_automation_execution", "performance_test_execution")) {
                jdbc.sql("CREATE TABLE " + table + " (project_id VARCHAR, status VARCHAR, finished_at TIMESTAMP(3))").update();
            }
            jdbc.sql("INSERT INTO functional_test_run VALUES ('run', 'project')").update();
            ZoneId displayZone = ZoneId.of("Asia/Shanghai");
            LocalDate firstDay = LocalDate.of(2026, 10, 17);
            Instant from = firstDay.atStartOfDay(displayZone).toInstant();
            Instant until = firstDay.plusDays(days).atStartOfDay(displayZone).toInstant();
            List<Instant> instants = List.of(from.minusMillis(1), from, from.plusSeconds(1800),
                    from.plusSeconds(86400).minusMillis(1), from.plusSeconds(86400), until.minusMillis(1), until);
            for (int index = 0; index < instants.size(); index++) {
                Timestamp finished = Timestamp.from(instants.get(index));
                boolean passed = index != 2 && index != 4;
                jdbc.sql("INSERT INTO functional_test_run_case VALUES ('run', :status, :finished)")
                        .param("status", passed ? "PASSED" : "FAILED").param("finished", finished).update();
                for (String table : List.of("api_automation_execution", "performance_test_execution")) {
                    jdbc.sql("INSERT INTO " + table + " VALUES ('project', :status, :finished)")
                            .param("status", passed ? "SUCCEEDED" : "FAILED").param("finished", finished).update();
                }
            }
            ProjectDashboardRepository repository = new ProjectDashboardRepository(jdbc);
            var expectedTotal = new ProjectDashboardRepository.OutcomeCounts(3, 2);
            assertEquals(expectedTotal, repository.functionalOutcomes("project", from, until));
            assertEquals(expectedTotal, repository.automationOutcomes("project", from, until));
            assertEquals(expectedTotal, repository.performanceOutcomes("project", from, until));
            for (var source : List.of(repository.functionalTrend("project", from, until),
                    repository.automationTrend("project", from, until),
                    repository.performanceTrend("project", from, until))) {
                var points = repository.mergeTrend(firstDay, firstDay.plusDays(days - 1L), source);
                assertEquals(days, points.size());
                assertEquals(3, points.getFirst().completedExecutions());
                assertEquals(2, points.getFirst().passedExecutions());
                assertEquals(1, points.getFirst().failedExecutions());
                assertEquals(1, points.get(1).failedExecutions());
                assertEquals(1, points.getLast().passedExecutions());
                assertEquals(5, points.stream().mapToLong(point -> point.completedExecutions()).sum());
                assertEquals(3, points.stream().mapToLong(point -> point.passedExecutions()).sum());
                assertEquals(2, points.stream().mapToLong(point -> point.failedExecutions()).sum());
            }
        } finally {
            dataSource.destroy();
        }
    }
}
