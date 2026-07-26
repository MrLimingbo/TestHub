package io.testhub.platform.apiautomation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.springframework.stereotype.Service;

/** Builds the exact bytes returned by the report download before deriving all integrity metadata from them. */
@Service
public class ApiAutomationExecutionReportArtifactService {
    public static final String SHA256_HEADER = "X-TestHub-Report-SHA256";
    private static final String CONTRACT = "testhub.api-automation.execution-report-artifact";
    private static final int ARTIFACT_VERSION = 1;
    private final ApiAutomationExecutionService executions;
    private final ObjectMapper json;

    public ApiAutomationExecutionReportArtifactService(ApiAutomationExecutionService executions, ObjectMapper json) {
        this.executions = executions;
        this.json = json;
    }

    public Artifact artifact(AuthenticatedPrincipal principal, String projectId, String executionId) {
        ApiAutomationExecutionReport report = executions.report(principal, projectId, executionId);
        byte[] content;
        try {
            content = json.writeValueAsBytes(report);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成接口自动化脱敏执行报告", exception);
        }
        String sha256 = sha256(content);
        String filename = "testhub-api-automation-execution-" + executionId + ".json";
        return new Artifact(content, new Metadata(CONTRACT, ARTIFACT_VERSION, report.contract(), report.reportVersion(), sha256,
                '"' + sha256 + '"', "application/json", filename));
    }

    public boolean matchesIfNoneMatch(String ifNoneMatch, Metadata metadata) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        return Arrays.stream(ifNoneMatch.split(",")).map(String::trim)
                .anyMatch(candidate -> "*".equals(candidate) || metadata.eTag().equals(candidate)
                        || ("W/" + metadata.eTag()).equals(candidate));
    }

    private String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", exception);
        }
    }

    public record Artifact(byte[] content, Metadata metadata) {
        public Artifact { content = content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }

    /** Values are safe to show in browser and CI logs because they describe only the fixed report artifact. */
    public record Metadata(String contract, int artifactVersion, String reportContract, int reportVersion,
                           String sha256, String eTag, String mediaType, String filename) {
    }
}
