package io.testhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.apiautomation.ApiRunnerEventConsumer;
import io.testhub.platform.apiautomation.ApiAutomationExecutionReportArtifactService;
import io.testhub.platform.apiautomation.ApiRunnerHttpResultConsumer;
import io.testhub.platform.apiautomation.ApiRunnerLogConsumer;
import io.testhub.platform.apiautomation.ApiRunnerScriptResultConsumer;
import io.testhub.platform.apiautomation.ApiRunnerStepResultConsumer;
import io.testhub.platform.apiautomation.ApiAutomationExecutionTimeoutMonitor;
import io.testhub.platform.apiautomation.ApiRunnerOutboxDispatcher;
import io.testhub.platform.apiautomation.ApiRunnerProtocol;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiAutomationExecutionFlowTests {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ApiRunnerEventConsumer runnerEvents;
    @Autowired private ApiRunnerHttpResultConsumer runnerHttpResults;
    @Autowired private ApiRunnerStepResultConsumer runnerStepResults;
    @Autowired private ApiRunnerScriptResultConsumer runnerScriptResults;
    @Autowired private ApiRunnerLogConsumer runnerLogs;
    @Autowired private ApiAutomationExecutionTimeoutMonitor timeoutMonitor;
    @Autowired private ApiRunnerOutboxDispatcher outboxDispatcher;

    @AfterEach
    void clean() {
        jdbc.sql("DELETE FROM api_automation_execution WHERE scenario_name LIKE 'Runner 执行验收 %'").update();
        jdbc.sql("DELETE FROM api_automation_scenario WHERE name LIKE 'Runner 执行验收 %'").update();
        jdbc.sql("""
                        DELETE FROM project_parameter
                        WHERE parameter_key IN ('runner_manifest_project_value', 'runner_manifest_env_secret',
                            'runner_runtime_project_value', 'runner_runtime_env_secret')
                        """).update();
        jdbc.sql("DELETE FROM audit_log WHERE detail LIKE '%Runner 执行验收%'").update();
    }

    @Test
    void freezesOnlyStructureAndVariableReferenceMetadataForEachExecution() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        addParameter(projectId, "runner_manifest_project_value", "project-parameter-value-should-not-copy", false);
        addParameter(projectId, "runner_manifest_env_secret", "opaque-environment-secret-should-not-copy", true);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 安全执行清单","environmentCode":"test","failureStrategy":"CONTINUE",
                                "variables":{"baseUrl":"https://api.example.test","credentialLeakProbe":"scenario-value-should-not-copy"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1","header":"Authorization should-not-copy","projectProbe":"{{runner_manifest_project_value}}","secretProbe":"{{runner_manifest_env_secret}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();
        String executionId = createExecution(administrator, projectId, scenarioId, 300);

        MvcResult response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.scenarioId").value(scenarioId))
                .andExpect(jsonPath("$.data.scenarioVersion").value(1))
                .andExpect(jsonPath("$.data.stepCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.manifestVersion").value(5))
                .andExpect(jsonPath("$.data.snapshot.scripts.preRequestConfigured").value(false))
                .andExpect(jsonPath("$.data.snapshot.scripts.postResponseConfigured").value(false))
                .andExpect(jsonPath("$.data.snapshot.environmentCode").value("test"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].name").value("读取订单"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].requestMethod").value("GET"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].configKeys[0]").value("header"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].configKeys[1]").value("method"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].configKeys[2]").value("projectProbe"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].configKeys[3]").value("secretProbe"))
                .andExpect(jsonPath("$.data.snapshot.steps[0].configKeys[4]").value("url"))
                .andReturn();
        JsonNode references = json(response).at("/data/snapshot/variableReferences");
        JsonNode secretReference = java.util.stream.StreamSupport.stream(references.spliterator(), false)
                .filter(item -> "runner_manifest_env_secret".equals(item.path("name").asText())).findFirst().orElseThrow();
        JsonNode parameterReference = java.util.stream.StreamSupport.stream(references.spliterator(), false)
                .filter(item -> "runner_manifest_project_value".equals(item.path("name").asText())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("ENVIRONMENT_SECRET", secretReference.path("source").asText());
        org.junit.jupiter.api.Assertions.assertTrue(secretReference.path("secret").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("secretProbe", secretReference.at("/locations/0/configKey").asText());
        org.junit.jupiter.api.Assertions.assertEquals("PROJECT_PARAMETER", parameterReference.path("source").asText());
        org.junit.jupiter.api.Assertions.assertFalse(parameterReference.path("secret").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("projectProbe", parameterReference.at("/locations/0/configKey").asText());
        String renderedManifest = response.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertTrue(renderedManifest.contains("baseUrl"));
        org.junit.jupiter.api.Assertions.assertTrue(renderedManifest.contains("runner_manifest_project_value"));
        org.junit.jupiter.api.Assertions.assertTrue(renderedManifest.contains("runner_manifest_env_secret"));
        org.junit.jupiter.api.Assertions.assertTrue(renderedManifest.contains("ENVIRONMENT_SECRET"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedManifest.contains("https://api.example.test"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedManifest.contains("scenario-value-should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedManifest.contains("Authorization should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedManifest.contains("project-parameter-value-should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedManifest.contains("opaque-environment-secret-should-not-copy"));

        String content = jdbc.sql("SELECT content FROM api_automation_execution_manifest WHERE execution_id = :executionId")
                .param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("https://api.example.test"));
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("scenario-value-should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("Authorization should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("project-parameter-value-should-not-copy"));
        org.junit.jupiter.api.Assertions.assertFalse(content.contains("opaque-environment-secret-should-not-copy"));
        String outboxPayload = jdbc.sql("SELECT payload FROM api_runner_outbox WHERE execution_id = :executionId AND message_type = 'TASK'")
                .param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertFalse(outboxPayload.contains("runner_manifest"));
    }

    @Test
    void freezesReferencedNonSecretValuesInternallyAndWithholdsEveryEnvironmentSecretValue() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String projectValue = "frozen-project-runtime-value";
        String secretValue = "opaque-runtime-secret-must-never-be-read";
        addParameter(projectId, "runner_runtime_project_value", projectValue, false);
        addParameter(projectId, "runner_runtime_env_secret", secretValue, true);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 非敏感变量快照","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://frozen.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET",
                                "url":"{{baseUrl}}/orders","projectProbe":"{{runner_runtime_project_value}}",
                                "secretProbe":"{{runner_runtime_env_secret}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();
        String executionId = createExecution(administrator, projectId, scenarioId, 300);

        MvcResult response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/runtime-snapshot",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshot.snapshotVersion").value(1))
                .andExpect(jsonPath("$.data.snapshot.environmentCode").value("test"))
                .andExpect(jsonPath("$.data.variableCount").value(2))
                .andExpect(jsonPath("$.data.withheldSecretCount").value(1))
                .andExpect(jsonPath("$.data.snapshot.withheldSecretNames[0]").value("runner_runtime_env_secret"))
                .andReturn();
        JsonNode variables = json(response).at("/data/snapshot/variables");
        JsonNode scenarioVariable = java.util.stream.StreamSupport.stream(variables.spliterator(), false)
                .filter(item -> "baseUrl".equals(item.path("name").asText())).findFirst().orElseThrow();
        JsonNode projectVariable = java.util.stream.StreamSupport.stream(variables.spliterator(), false)
                .filter(item -> "runner_runtime_project_value".equals(item.path("name").asText())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("SCENARIO", scenarioVariable.path("source").asText());
        org.junit.jupiter.api.Assertions.assertEquals("PROJECT_PARAMETER", projectVariable.path("source").asText());
        String renderedSummary = response.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(renderedSummary.contains("https://frozen.example.test"));
        org.junit.jupiter.api.Assertions.assertFalse(renderedSummary.contains(projectValue));
        org.junit.jupiter.api.Assertions.assertFalse(renderedSummary.contains(secretValue));

        String frozen = jdbc.sql("SELECT content FROM api_automation_execution_runtime_snapshot WHERE execution_id = :executionId")
                .param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(frozen.contains("https://frozen.example.test"));
        org.junit.jupiter.api.Assertions.assertTrue(frozen.contains(projectValue));
        org.junit.jupiter.api.Assertions.assertTrue(frozen.contains("runner_runtime_env_secret"));
        org.junit.jupiter.api.Assertions.assertFalse(frozen.contains(secretValue));
        String checksum = jdbc.sql("SELECT checksum FROM api_automation_execution_runtime_snapshot WHERE execution_id = :executionId")
                .param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(64, checksum.length());

        jdbc.sql("""
                        UPDATE project_parameter SET parameter_value = 'changed-after-submit'
                        WHERE project_id = :projectId AND parameter_key = 'runner_runtime_project_value'
                        """).param("projectId", projectId).update();
        String persisted = jdbc.sql("SELECT content FROM api_automation_execution_runtime_snapshot WHERE execution_id = :executionId")
                .param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(frozen, persisted);
        org.junit.jupiter.api.Assertions.assertTrue(persisted.contains(projectValue));
        org.junit.jupiter.api.Assertions.assertFalse(persisted.contains("changed-after-submit"));
    }

    @Test
    void rejectsExecutionWhenScenarioReferencesAnUnavailableVariableWithoutCreatingQueueRecords() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 未绑定变量","environmentCode":"test","failureStrategy":"STOP","variables":{},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"https://api.example.test/orders/{{missingToken}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();

        csrf = csrf(administrator);
        mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isBadRequest());
        Integer executions = jdbc.sql("SELECT COUNT(*) FROM api_automation_execution WHERE scenario_id = :scenarioId")
                .param("scenarioId", scenarioId).query(Integer.class).single();
        Integer outbox = jdbc.sql("""
                        SELECT COUNT(*) FROM api_runner_outbox outbox
                        JOIN api_automation_execution execution ON execution.id = outbox.execution_id
                        WHERE execution.scenario_id = :scenarioId
                        """).param("scenarioId", scenarioId).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, executions);
        org.junit.jupiter.api.Assertions.assertEquals(0, outbox);
    }

    @Test
    void recordsStatusExtractionAsRunnerLocalWithoutPersistingItsFutureValue() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 局部状态变量","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"创建订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}},
                                {"id":"extract-status","type":"EXTRACT","name":"提取状态","config":{"source":"LAST_STATUS_CODE","variable":"lastStatus"}},
                                {"id":"assert-status","type":"ASSERT","name":"断言状态","config":{"source":"LAST_STATUS_CODE","expectedStatus":201}},
                                {"id":"request-status","type":"REQUEST","name":"读取状态","config":{"method":"GET","url":"{{baseUrl}}/status/{{lastStatus}}"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();
        String executionId = createExecution(administrator, projectId, scenarioId, 300);

        MvcResult manifest = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/manifest", projectId,
                        executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        JsonNode localReference = java.util.stream.StreamSupport.stream(json(manifest).at("/data/snapshot/variableReferences").spliterator(), false)
                .filter(item -> "lastStatus".equals(item.path("name").asText())).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("RUNNER_LOCAL", localReference.path("source").asText());
        org.junit.jupiter.api.Assertions.assertFalse(localReference.path("secret").asBoolean());
        org.junit.jupiter.api.Assertions.assertEquals("request-status", localReference.at("/locations/0/stepId").asText());

        MvcResult snapshot = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/runtime-snapshot", projectId,
                        executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(snapshot.getResponse().getContentAsString().contains("lastStatus"));
    }

    @Test
    void queuesAScopedExecutionAndAppliesRunnerRejectionWithoutLeakingCredentials() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 订单回归","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();

        csrf = csrf(administrator);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":300}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.scenarioId").value(scenarioId)).andReturn();
        String executionId = json(execution).at("/data/id").asText();
        String taskId = json(execution).at("/data/taskId").asText();
        String payload = jdbc.sql("SELECT payload FROM api_runner_outbox WHERE id = :taskId")
                .param("taskId", taskId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(payload.contains("testhub.api-runner.task"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.toLowerCase().contains("password"));
        org.junit.jupiter.api.Assertions.assertFalse(payload.toLowerCase().contains("authorization"));

        runnerLogs.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "contract", ApiRunnerProtocol.LOG_CONTRACT, "protocolVersion", ApiRunnerProtocol.VERSION,
                "taskId", taskId, "executionId", executionId, "runnerId", "api-runner-test", "level", "INFO",
                "code", "TASK_RECEIVED", "message", "Task accepted for safety precheck.",
                "occurredAt", Instant.now().toString()
        )).withStreamKey(ApiRunnerProtocol.LOG_STREAM));
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "contract", ApiRunnerProtocol.LOG_CONTRACT, "protocolVersion", ApiRunnerProtocol.VERSION,
                "taskId", taskId, "executionId", executionId, "runnerId", "api-runner-test", "level", "INFO",
                "code", "TASK_RECEIVED", "message", "Bearer never-persist-this-secret", "occurredAt", Instant.now().toString()
        )).withStreamKey(ApiRunnerProtocol.LOG_STREAM));
        runnerLogs.consume();
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/logs", projectId, executionId)
                .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].code").value("MANIFEST_FROZEN"))
                .andExpect(jsonPath("$.data[1].code").value("QUEUED"))
                .andExpect(jsonPath("$.data[2].code").value("TASK_RECEIVED"))
                .andExpect(jsonPath("$.data[2].message").value("Task accepted for safety precheck."));
        Integer unsafeLogCount = jdbc.sql("""
                        SELECT COUNT(*) FROM api_automation_execution_log
                        WHERE execution_id = :executionId AND message LIKE '%never-persist-this-secret%'
                        """).param("executionId", executionId).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(0, unsafeLogCount);

        runnerEvents.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "contract", ApiRunnerProtocol.EVENT_CONTRACT, "protocolVersion", ApiRunnerProtocol.VERSION,
                "taskId", taskId, "executionId", executionId, "runnerId", "api-runner-test", "type", "REJECTED",
                "code", "EXECUTION_DISABLED", "message", "The execution engine is disabled for this verification.",
                "occurredAt", Instant.now().toString()
        )).withStreamKey(ApiRunnerProtocol.EVENT_STREAM));
        runnerEvents.consume();

        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.statusCode").value("EXECUTION_DISABLED"))
                .andExpect(jsonPath("$.data.runnerId").value("api-runner-test"));
    }

    @Test
    void persistsOnlyRedactedHttpStepResultsForTheFrozenRequestDefinition() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Runner 执行验收 脱敏 HTTP 结果");
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        runnerHttpResults.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.HTTP_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.HTTP_RESULT_VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", "request-order"), Map.entry("requestSequence", "1"), Map.entry("method", "GET"),
                Map.entry("attemptNumber", "1"), Map.entry("outcome", "FAILED"), Map.entry("statusCode", "503"), Map.entry("durationMillis", "36"),
                Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.HTTP_RESULT_STREAM));
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.HTTP_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.HTTP_RESULT_VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", "request-order"), Map.entry("requestSequence", "1"), Map.entry("method", "GET"),
                Map.entry("attemptNumber", "2"), Map.entry("outcome", "SUCCEEDED"), Map.entry("statusCode", "204"), Map.entry("durationMillis", "36"),
                Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.HTTP_RESULT_STREAM));
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.HTTP_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.HTTP_RESULT_VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", "request-order"), Map.entry("requestSequence", "2"), Map.entry("method", "GET"),
                Map.entry("attemptNumber", "1"), Map.entry("outcome", "SUCCEEDED"), Map.entry("statusCode", "204"), Map.entry("durationMillis", "36"),
                Map.entry("url", "https://must-not-be-persisted.example/private"), Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.HTTP_RESULT_STREAM));
        runnerHttpResults.consume();

        MvcResult response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/http-results",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].stepId").value("request-order"))
                .andExpect(jsonPath("$.data[0].attemptNumber").value(1))
                .andExpect(jsonPath("$.data[0].method").value("GET"))
                .andExpect(jsonPath("$.data[0].outcome").value("FAILED"))
                .andExpect(jsonPath("$.data[0].statusCode").value(503))
                .andExpect(jsonPath("$.data[1].attemptNumber").value(2))
                .andExpect(jsonPath("$.data[1].outcome").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[1].statusCode").value(204)).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(response.getResponse().getContentAsString()
                .contains("must-not-be-persisted"));
        List<String> persisted = jdbc.sql("SELECT CONCAT(request_sequence, ':', attempt_number, ':', step_id, ':', method, ':', outcome, ':', status_code, ':', duration_millis)\n"
                        + "FROM api_automation_execution_http_result WHERE execution_id = :executionId ORDER BY request_sequence, attempt_number")
                .param("executionId", executionId).query(String.class).list();
        org.junit.jupiter.api.Assertions.assertEquals(List.of("1:1:request-order:GET:FAILED:503:36", "1:2:request-order:GET:SUCCEEDED:204:36"), persisted);
    }

    @Test
    void aggregatesBoundedParallelRequestsInTheExportSafeReport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 并行报告","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"parallel-status","type":"PARALLEL","name":"并发状态","config":{"requests":[
                                {"method":"GET","url":"{{baseUrl}}/health","maxAttempts":1},
                                {"method":"GET","url":"{{baseUrl}}/version","maxAttempts":2}]}},
                                {"id":"request-after","type":"REQUEST","name":"后续请求","config":{"method":"GET","url":"{{baseUrl}}/after"}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        runnerHttpResults.consume();
        addHttpResult(taskId, executionId, "parallel-status", 1, 1, "SUCCEEDED", 204, 10);
        addHttpResult(taskId, executionId, "parallel-status", 2, 1, "FAILED", 503, 12);
        addHttpResult(taskId, executionId, "parallel-status", 2, 2, "SUCCEEDED", 204, 13);
        addHttpResult(taskId, executionId, "request-after", 3, 1, "SUCCEEDED", 200, 9);
        runnerHttpResults.consume();

        MvcResult report = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportVersion").value(5))
                .andExpect(jsonPath("$.data.parallelGroups.length()").value(1))
                .andExpect(jsonPath("$.data.parallelGroups[0].stepId").value("parallel-status"))
                .andExpect(jsonPath("$.data.parallelGroups[0].configuredBranchCount").value(2))
                .andExpect(jsonPath("$.data.parallelGroups[0].completedBranchCount").value(2))
                .andExpect(jsonPath("$.data.parallelGroups[0].succeededBranchCount").value(2))
                .andExpect(jsonPath("$.data.parallelGroups[0].failedBranchCount").value(0))
                .andExpect(jsonPath("$.data.parallelGroups[0].attemptCount").value(3))
                .andExpect(jsonPath("$.data.parallelGroups[0].retryAttemptCount").value(1))
                .andExpect(jsonPath("$.data.parallelGroups[0].firstRequestSequence").value(1))
                .andExpect(jsonPath("$.data.parallelGroups[0].lastRequestSequence").value(2))
                .andExpect(jsonPath("$.data.parallelGroups[0].totalDurationMillis").value(35)).andReturn();
        String rendered = report.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("api.example.test"));
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("{{baseUrl}}"));

        MvcResult integrity = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report/integrity",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contract").value("testhub.api-automation.execution-report-artifact"))
                .andExpect(jsonPath("$.data.artifactVersion").value(1))
                .andExpect(jsonPath("$.data.reportContract").value("testhub.api-automation.execution-report"))
                .andExpect(jsonPath("$.data.reportVersion").value(5)).andReturn();
        String sha256 = json(integrity).at("/data/sha256").asText();
        String eTag = json(integrity).at("/data/eTag").asText();
        org.junit.jupiter.api.Assertions.assertTrue(sha256.matches("[0-9a-f]{64}"));
        org.junit.jupiter.api.Assertions.assertEquals("\"" + sha256 + "\"", eTag);

        MvcResult exported = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report/export",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.contract").value("testhub.api-automation.execution-report"))
                .andExpect(jsonPath("$.reportVersion").value(5))
                .andExpect(jsonPath("$.parallelGroups[0].stepId").value("parallel-status"))
                .andExpect(jsonPath("$.parallelGroups[0].retryAttemptCount").value(1)).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(MediaType.APPLICATION_JSON_VALUE, exported.getResponse().getContentType());
        org.junit.jupiter.api.Assertions.assertTrue(exported.getResponse().getHeader("Content-Disposition")
                .contains("attachment; filename=\"testhub-api-automation-execution-" + executionId + ".json\""));
        org.junit.jupiter.api.Assertions.assertEquals(sha256,
                exported.getResponse().getHeader(ApiAutomationExecutionReportArtifactService.SHA256_HEADER));
        org.junit.jupiter.api.Assertions.assertEquals(eTag, exported.getResponse().getHeader(HttpHeaders.ETAG));
        org.junit.jupiter.api.Assertions.assertEquals(sha256, java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(exported.getResponse().getContentAsByteArray())));
        org.junit.jupiter.api.Assertions.assertFalse(exported.getResponse().getContentAsString().contains("api.example.test"));

        MvcResult notModified = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report/export",
                        projectId, executionId).cookie(administrator.sessionCookie()).header(HttpHeaders.IF_NONE_MATCH, eTag))
                .andExpect(status().isNotModified()).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(sha256,
                notModified.getResponse().getHeader(ApiAutomationExecutionReportArtifactService.SHA256_HEADER));
        org.junit.jupiter.api.Assertions.assertEquals(eTag, notModified.getResponse().getHeader(HttpHeaders.ETAG));
    }

    @Test
    void persistsOnlyFixedExtractAndAssertionResultsForKnownFrozenSteps() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"Runner 执行验收 步骤结果","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}},
                                {"id":"extract-status","type":"EXTRACT","name":"提取状态","config":{"source":"LAST_STATUS_CODE","variable":"lastStatus"}},
                                {"id":"assert-status","type":"ASSERT","name":"断言状态","config":{"source":"LAST_STATUS_CODE","expectedStatus":204}}]}
                                """))
                .andExpect(status().isOk()).andReturn();
        String scenarioId = json(scenario).at("/data/id").asText();
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        runnerStepResults.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.STEP_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", "extract-status"), Map.entry("stepSequence", "2"), Map.entry("type", "EXTRACT"),
                Map.entry("outcome", "COMPLETED"), Map.entry("observedStatusCode", "204"), Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.STEP_RESULT_STREAM));
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.STEP_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", "assert-status"), Map.entry("stepSequence", "3"), Map.entry("type", "ASSERT"),
                Map.entry("outcome", "PASSED"), Map.entry("observedStatusCode", "204"), Map.entry("response", "must-not-be-persisted"),
                Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.STEP_RESULT_STREAM));
        runnerStepResults.consume();

        MvcResult response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/step-results",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].stepId").value("extract-status"))
                .andExpect(jsonPath("$.data[0].stepType").value("EXTRACT"))
                .andExpect(jsonPath("$.data[0].outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].observedStatusCode").value(204)).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(response.getResponse().getContentAsString().contains("must-not-be-persisted"));
    }

    @Test
    void persistsOnlyAggregatePmScriptAssertionsAndAddsThemToTheExportSafeReport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScriptedScenario(administrator, projectId, "Runner 执行验收 脚本断言统计");
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        runnerScriptResults.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.SCRIPT_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("phase", "PRE_REQUEST"), Map.entry("testCount", "3"), Map.entry("passedCount", "2"),
                Map.entry("failedCount", "1"), Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.SCRIPT_RESULT_STREAM));
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.SCRIPT_RESULT_CONTRACT), Map.entry("protocolVersion", ApiRunnerProtocol.VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("phase", "PRE_REQUEST"), Map.entry("testCount", "3"), Map.entry("passedCount", "2"),
                Map.entry("failedCount", "1"), Map.entry("testName", "must-not-be-persisted"),
                Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.SCRIPT_RESULT_STREAM));
        runnerScriptResults.consume();

        MvcResult response = awaitScriptResultCount(administrator, projectId, executionId, 1);
        JsonNode scriptResults = json(response).at("/data");
        org.junit.jupiter.api.Assertions.assertEquals("PRE_REQUEST", scriptResults.get(0).path("phase").asText());
        org.junit.jupiter.api.Assertions.assertEquals(3, scriptResults.get(0).path("testCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(2, scriptResults.get(0).path("passedCount").asInt());
        org.junit.jupiter.api.Assertions.assertEquals(1, scriptResults.get(0).path("failedCount").asInt());
        org.junit.jupiter.api.Assertions.assertFalse(response.getResponse().getContentAsString().contains("must-not-be-persisted"));

        MvcResult report = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportVersion").value(5))
                .andExpect(jsonPath("$.data.failureStrategy.strategy").value("STOP"))
                .andExpect(jsonPath("$.data.failureStrategy.continuedFailureCount").value(0))
                .andExpect(jsonPath("$.data.summary.scriptResultCount").value(1))
                .andExpect(jsonPath("$.data.summary.scriptTestCount").value(3))
                .andExpect(jsonPath("$.data.summary.scriptPassedCount").value(2))
                .andExpect(jsonPath("$.data.summary.scriptFailedCount").value(1))
                .andExpect(jsonPath("$.data.scriptResults.length()").value(1)).andReturn();
        org.junit.jupiter.api.Assertions.assertFalse(report.getResponse().getContentAsString().contains("must-not-be-persisted"));
    }

    @Test
    void addsOnlyTheFixedContinuationCountToTheExportSafeReport() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Runner 执行验收 继续策略报告", "CONTINUE");
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        runnerLogs.consume();
        for (int index = 0; index < 2; index++) {
            redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                    "contract", ApiRunnerProtocol.LOG_CONTRACT, "protocolVersion", ApiRunnerProtocol.VERSION,
                    "taskId", taskId, "executionId", executionId, "runnerId", "api-runner-test", "level", "WARN",
                    "code", "FAILURE_CONTINUED",
                    "message", "The configured continue strategy allowed later bounded steps to run after a failure.",
                    "occurredAt", Instant.now().plusMillis(index).toString()
            )).withStreamKey(ApiRunnerProtocol.LOG_STREAM));
        }
        runnerLogs.consume();

        MvcResult report = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/report",
                        projectId, executionId).cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reportVersion").value(5))
                .andExpect(jsonPath("$.data.failureStrategy.strategy").value("CONTINUE"))
                .andExpect(jsonPath("$.data.failureStrategy.continuedFailureCount").value(2)).andReturn();
        String rendered = report.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("The configured continue strategy"));
        org.junit.jupiter.api.Assertions.assertFalse(rendered.contains("api.example.test"));
    }

    @Test
    void cancelsPendingWorkOrReliablyRequestsRunnerCancellationWithoutSecrets() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Runner 执行验收 取消任务");
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        jdbc.sql("UPDATE api_runner_outbox SET status = 'SENT', locked_at = NULL WHERE id = :taskId")
                .param("taskId", taskId).update();
        jdbc.sql("UPDATE api_automation_execution SET status = 'RUNNING' WHERE id = :executionId")
                .param("executionId", executionId).update();
        Csrf csrf = csrf(administrator);
        mvc.perform(delete("/api/v1/projects/{projectId}/api-automation/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie(), csrf.cookie()).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLING"))
                .andExpect(jsonPath("$.data.statusCode").value("CANCEL_REQUESTED"));
        String cancellationPayload = jdbc.sql("""
                        SELECT payload FROM api_runner_outbox
                        WHERE execution_id = :executionId AND message_type = 'CANCEL'
                        """).param("executionId", executionId).query(String.class).single();
        org.junit.jupiter.api.Assertions.assertTrue(cancellationPayload.contains("testhub.api-runner.cancel"));
        org.junit.jupiter.api.Assertions.assertFalse(cancellationPayload.toLowerCase().contains("password"));
        org.junit.jupiter.api.Assertions.assertFalse(cancellationPayload.toLowerCase().contains("authorization"));

        runnerEvents.consume();
        redis.opsForStream().add(StreamRecords.mapBacked(Map.of(
                "contract", ApiRunnerProtocol.EVENT_CONTRACT, "protocolVersion", ApiRunnerProtocol.VERSION,
                "taskId", taskId, "executionId", executionId, "runnerId", "api-runner-test", "type", "CANCELLED",
                "code", "USER_REQUESTED", "message", "Cancellation accepted without user content execution.",
                "occurredAt", Instant.now().toString()
        )).withStreamKey(ApiRunnerProtocol.EVENT_STREAM));
        runnerEvents.consume();
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.statusCode").value("USER_REQUESTED"));
    }

    @Test
    void timesOutExpiredWorkAndStopsAfterTheConfiguredDeliveryBudget() throws Exception {
        LoginSession administrator = login();
        String projectId = projectId(administrator);
        String scenarioId = createScenario(administrator, projectId, "Runner 执行验收 超时与重试");
        String executionId = createExecution(administrator, projectId, scenarioId, 300);
        String taskId = executionTaskId(projectId, executionId);

        jdbc.sql("UPDATE api_runner_outbox SET status = 'SENT', locked_at = NULL WHERE id = :taskId")
                .param("taskId", taskId).update();
        jdbc.sql("""
                        UPDATE api_automation_execution
                        SET status = 'RUNNING', deadline_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
                        WHERE id = :executionId
                        """).param("executionId", executionId).update();
        timeoutMonitor.expireDueExecutions();
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.data.statusCode").value("EXECUTION_TIMEOUT"));
        Integer timeoutCommands = jdbc.sql("""
                        SELECT COUNT(*) FROM api_runner_outbox
                        WHERE execution_id = :executionId AND message_type = 'CANCEL'
                        """).param("executionId", executionId).query(Integer.class).single();
        org.junit.jupiter.api.Assertions.assertEquals(1, timeoutCommands);

        jdbc.sql("""
                        UPDATE api_automation_execution SET status = 'QUEUED', status_code = NULL, status_message = '',
                            finished_at = NULL
                        WHERE id = :executionId
                        """).param("executionId", executionId).update();
        jdbc.sql("""
                        UPDATE api_runner_outbox SET status = 'DISPATCHING', dispatch_attempts = max_dispatch_attempts,
                            locked_at = CURRENT_TIMESTAMP(3)
                        WHERE id = :taskId
                        """).param("taskId", taskId).update();
        jdbc.sql("""
                        UPDATE api_runner_outbox SET status = 'CANCELLED'
                        WHERE execution_id = :executionId AND message_type = 'CANCEL'
                        """).param("executionId", executionId).update();
        jdbc.sql("""
                        UPDATE api_runner_outbox SET status = 'FAILED', locked_at = NULL,
                            last_error = 'Runner Stream 投递失败：RedisConnectionFailureException'
                        WHERE id = :taskId
                        """).param("taskId", taskId).update();
        outboxDispatcher.dispatchPending();
        mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}", projectId, executionId)
                        .cookie(administrator.sessionCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.statusCode").value("DELIVERY_RETRY_EXHAUSTED"));
    }

    private String createScenario(LoginSession administrator, String projectId, String name) throws Exception {
        return createScenario(administrator, projectId, name, "STOP");
    }

    private String createScenario(LoginSession administrator, String projectId, String name, String failureStrategy) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"%s","environmentCode":"test","failureStrategy":"%s",
                                "variables":{"baseUrl":"https://api.example.test"},"steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}}]}
                                """.formatted(name, failureStrategy)))
                .andExpect(status().isOk()).andReturn();
        return json(scenario).at("/data/id").asText();
    }

    private String createScriptedScenario(LoginSession administrator, String projectId, String name) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult scenario = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios", projectId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("""
                                {"name":"%s","environmentCode":"test","failureStrategy":"STOP",
                                "variables":{"baseUrl":"https://api.example.test"},
                                "preRequestScript":"pm.test('ready', function () { pm.expect(true).to.be.true; });","steps":[
                                {"id":"request-order","type":"REQUEST","name":"读取订单","config":{"method":"GET","url":"{{baseUrl}}/orders/1"}}]}
                                """.formatted(name)))
                .andExpect(status().isOk()).andReturn();
        return json(scenario).at("/data/id").asText();
    }

    private String createExecution(LoginSession administrator, String projectId, String scenarioId, int timeoutSeconds) throws Exception {
        Csrf csrf = csrf(administrator);
        MvcResult execution = mvc.perform(post("/api/v1/projects/{projectId}/api-automation/scenarios/{scenarioId}/executions", projectId, scenarioId)
                        .contentType(MediaType.APPLICATION_JSON).cookie(administrator.sessionCookie(), csrf.cookie())
                        .header(csrf.headerName(), csrf.token()).content("{\"timeoutSeconds\":" + timeoutSeconds + "}"))
                .andExpect(status().isOk()).andReturn();
        return json(execution).at("/data/id").asText();
    }

    private String executionTaskId(String projectId, String executionId) {
        return jdbc.sql("SELECT task_id FROM api_automation_execution WHERE project_id = :projectId AND id = :executionId")
                .param("projectId", projectId).param("executionId", executionId).query(String.class).single();
    }

    private void addHttpResult(String taskId, String executionId, String stepId, int requestSequence, int attemptNumber,
                               String outcome, int statusCode, long durationMillis) {
        redis.opsForStream().add(StreamRecords.mapBacked(Map.ofEntries(
                Map.entry("contract", ApiRunnerProtocol.HTTP_RESULT_CONTRACT),
                Map.entry("protocolVersion", ApiRunnerProtocol.HTTP_RESULT_VERSION),
                Map.entry("taskId", taskId), Map.entry("executionId", executionId), Map.entry("runnerId", "api-runner-test"),
                Map.entry("stepId", stepId), Map.entry("requestSequence", String.valueOf(requestSequence)),
                Map.entry("method", "GET"), Map.entry("attemptNumber", String.valueOf(attemptNumber)),
                Map.entry("outcome", outcome), Map.entry("statusCode", String.valueOf(statusCode)),
                Map.entry("durationMillis", String.valueOf(durationMillis)), Map.entry("occurredAt", Instant.now().toString())
        )).withStreamKey(ApiRunnerProtocol.HTTP_RESULT_STREAM));
    }

    private void addParameter(String projectId, String key, String value, boolean secret) {
        String organizationId = jdbc.sql("SELECT organization_id FROM project WHERE id = :projectId")
                .param("projectId", projectId).query(String.class).single();
        String administratorId = jdbc.sql("SELECT id FROM user_account WHERE email = 'admin@testhub.local'")
                .query(String.class).single();
        jdbc.sql("""
                        INSERT INTO project_parameter
                            (id, organization_id, project_id, environment_code, parameter_key, parameter_value, secret, updated_by)
                        VALUES (:id, :organizationId, :projectId, 'test', :key, :value, :secret, :updatedBy)
                        ON DUPLICATE KEY UPDATE parameter_value = VALUES(parameter_value), secret = VALUES(secret),
                            updated_by = VALUES(updated_by), updated_at = CURRENT_TIMESTAMP(3)
                        """).param("id", UlidCreator.getUlid().toString()).param("organizationId", organizationId)
                .param("projectId", projectId).param("key", key).param("value", value).param("secret", secret)
                .param("updatedBy", administratorId).update();
    }

    private LoginSession login() throws Exception {
        MvcResult csrf = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode data = json(csrf).path("data");
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .cookie(csrf.getResponse().getCookie("XSRF-TOKEN")).header(data.path("headerName").asText(), data.path("token").asText())
                        .content("{\"email\":\"admin@testhub.local\",\"password\":\"TestHub2026!\"}"))
                .andExpect(status().isOk()).andReturn();
        return new LoginSession(login.getResponse().getCookie("TESTHUB_SESSION"));
    }

    private Csrf csrf(LoginSession session) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        JsonNode data = json(result).path("data");
        return new Csrf(result.getResponse().getCookie("XSRF-TOKEN"), data.path("headerName").asText(), data.path("token").asText());
    }

    private String projectId(LoginSession session) throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/access/context").cookie(session.sessionCookie())).andExpect(status().isOk()).andReturn();
        String organizationId = json(context).at("/data/organizations/0/id").asText();
        return json(context).at("/data/projects/" + organizationId + "/0/id").asText();
    }

    private MvcResult awaitScriptResultCount(LoginSession administrator, String projectId, String executionId, int expectedCount)
            throws Exception {
        MvcResult response = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            runnerScriptResults.consume();
            response = mvc.perform(get("/api/v1/projects/{projectId}/api-automation/executions/{executionId}/script-results",
                            projectId, executionId).cookie(administrator.sessionCookie()))
                    .andExpect(status().isOk()).andReturn();
            if (json(response).at("/data").size() == expectedCount) return response;
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(response);
        org.junit.jupiter.api.Assertions.assertEquals(expectedCount, json(response).at("/data").size(),
                "Runner script result was not persisted before the verification deadline");
        return response;
    }

    private JsonNode json(MvcResult result) throws Exception { return objectMapper.readTree(result.getResponse().getContentAsString()); }
    private record LoginSession(Cookie sessionCookie) { }
    private record Csrf(Cookie cookie, String headerName, String token) { }
}
