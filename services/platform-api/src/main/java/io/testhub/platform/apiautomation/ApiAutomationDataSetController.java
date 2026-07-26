package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-automation/data-sets")
public class ApiAutomationDataSetController {
    private final ApiAutomationDataSetService dataSets;

    public ApiAutomationDataSetController(ApiAutomationDataSetService dataSets) {
        this.dataSets = dataSets;
    }

    @GetMapping
    public ApiResponse<List<ApiAutomationDataSet>> list(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(dataSets.list(principal(authentication), projectId));
    }

    @GetMapping("/{dataSetId}")
    public ApiResponse<ApiAutomationDataSetDetail> detail(Authentication authentication, @PathVariable String projectId,
                                                           @PathVariable String dataSetId) {
        return ApiResponse.of(dataSets.detail(principal(authentication), projectId, dataSetId));
    }

    @PostMapping
    public ApiResponse<ApiAutomationDataSetDetail> create(Authentication authentication, @PathVariable String projectId,
                                                           @Valid @RequestBody DataSetRequest request) {
        return ApiResponse.of(dataSets.create(principal(authentication), projectId, input(request)));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ApiAutomationDataSetDetail> importFile(Authentication authentication, @PathVariable String projectId,
                                                               @RequestParam("name") String name,
                                                               @RequestParam("sourceFormat") String sourceFormat,
                                                               @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(dataSets.importFile(principal(authentication), projectId,
                new ApiAutomationDataSetService.DataSetInput(name, sourceFormat, "uploaded-file"), file));
    }

    @PutMapping("/{dataSetId}")
    public ApiResponse<ApiAutomationDataSetDetail> update(Authentication authentication, @PathVariable String projectId,
                                                           @PathVariable String dataSetId, @Valid @RequestBody DataSetRequest request) {
        return ApiResponse.of(dataSets.update(principal(authentication), projectId, dataSetId, input(request)));
    }

    @DeleteMapping("/{dataSetId}")
    public ApiResponse<Void> delete(Authentication authentication, @PathVariable String projectId,
                                    @PathVariable String dataSetId) {
        dataSets.delete(principal(authentication), projectId, dataSetId);
        return ApiResponse.of(null);
    }

    @PostMapping("/{dataSetId}/scenarios/{scenarioId}/executions")
    public ApiResponse<ApiAutomationDataSetExecutionBatch> execute(Authentication authentication, @PathVariable String projectId,
                                                                     @PathVariable String dataSetId, @PathVariable String scenarioId,
                                                                     @Valid @RequestBody DataSetExecutionRequest request) {
        return ApiResponse.of(dataSets.createExecutions(principal(authentication), projectId, dataSetId, scenarioId,
                request.timeoutSeconds()));
    }

    private ApiAutomationDataSetService.DataSetInput input(DataSetRequest request) {
        return new ApiAutomationDataSetService.DataSetInput(request.name(), request.sourceFormat(), request.content());
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }

    public record DataSetRequest(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 12) String sourceFormat,
                                 @NotBlank @Size(max = 524_288) String content) {
    }

    public record DataSetExecutionRequest(@NotNull @Min(10) @Max(3600) Integer timeoutSeconds) {
    }
}
