package io.testhub.platform.apidesign;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-design")
public class ApiDesignController {
    private final ApiDesignService design;
    private final ApiInterchangeService interchange;
    private final ObjectMapper objectMapper;

    public ApiDesignController(ApiDesignService design, ApiInterchangeService interchange, ObjectMapper objectMapper) {
        this.design = design;
        this.interchange = interchange;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/catalogs") public ApiResponse<List<ApiCatalog>> catalogs(Authentication authentication, @PathVariable String projectId) { return ApiResponse.of(design.catalogs(principal(authentication), projectId)); }
    @PostMapping("/catalogs") public ApiResponse<ApiCatalog> createCatalog(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody CatalogRequest request) { return ApiResponse.of(design.createCatalog(principal(authentication), projectId, request.toInput())); }
    @GetMapping("/definitions") public ApiResponse<List<ApiDefinition>> definitions(Authentication authentication, @PathVariable String projectId, @RequestParam(required = false) String catalogId) { return ApiResponse.of(design.definitions(principal(authentication), projectId, catalogId)); }
    @GetMapping("/definitions/{apiId}") public ApiResponse<ApiDefinition> definition(Authentication authentication, @PathVariable String projectId, @PathVariable String apiId) { return ApiResponse.of(design.definition(principal(authentication), projectId, apiId)); }
    @PostMapping("/definitions") public ApiResponse<ApiDefinition> createDefinition(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody DefinitionRequest request) { return ApiResponse.of(design.createDefinition(principal(authentication), projectId, request.toInput())); }
    @GetMapping("/definitions/{apiId}/versions") public ApiResponse<List<ApiDefinitionVersion>> versions(Authentication authentication, @PathVariable String projectId, @PathVariable String apiId) { return ApiResponse.of(design.versions(principal(authentication), projectId, apiId)); }
    @PostMapping("/definitions/{apiId}/versions") public ApiResponse<ApiDefinitionVersion> createVersion(Authentication authentication, @PathVariable String projectId, @PathVariable String apiId, @Valid @RequestBody VersionRequest request) { return ApiResponse.of(design.createVersion(principal(authentication), projectId, apiId, request.toInput())); }
    @PostMapping("/definitions/{apiId}/versions/{versionId}/publish") public ApiResponse<ApiDefinitionVersion> publish(Authentication authentication, @PathVariable String projectId, @PathVariable String apiId, @PathVariable String versionId) { return ApiResponse.of(design.publish(principal(authentication), projectId, apiId, versionId)); }
    @PostMapping("/interchange/openapi/import") public ApiResponse<ApiInterchangeService.ImportResult> importOpenApi(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody OpenApiImportRequest request) { return ApiResponse.of(interchange.importOpenApi(principal(authentication), projectId, new ApiInterchangeService.OpenApiImportInput(request.catalogId(), request.document()))); }
    @PostMapping("/interchange/curl/import") public ApiResponse<ApiInterchangeService.ImportResult> importCurl(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody CurlImportRequest request) { return ApiResponse.of(interchange.importCurl(principal(authentication), projectId, new ApiInterchangeService.CurlImportInput(request.catalogId(), request.name(), request.command()))); }
    @PostMapping("/interchange/postman/import") public ApiResponse<ApiInterchangeService.ImportResult> importPostman(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody PostmanImportRequest request) { return ApiResponse.of(interchange.importPostman(principal(authentication), projectId, new ApiInterchangeService.PostmanImportInput(request.catalogId(), request.document()))); }
    @PostMapping("/interchange/har/import") public ApiResponse<ApiInterchangeService.ImportResult> importHar(Authentication authentication, @PathVariable String projectId, @Valid @RequestBody HarImportRequest request) { return ApiResponse.of(interchange.importHar(principal(authentication), projectId, new ApiInterchangeService.HarImportInput(request.catalogId(), request.document()))); }
    @GetMapping(value = "/interchange/openapi/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ByteArrayResource> exportOpenApi(Authentication authentication, @PathVariable String projectId, @RequestParam(required = false) String catalogId) throws Exception {
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(interchange.exportOpenApi(principal(authentication), projectId, catalogId));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("testhub-openapi.json", StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(body));
    }
    @GetMapping(value = "/interchange/postman/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ByteArrayResource> exportPostman(Authentication authentication, @PathVariable String projectId, @RequestParam(required = false) String catalogId) throws Exception {
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(interchange.exportPostman(principal(authentication), projectId, catalogId));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("testhub-postman-collection.json", StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(body));
    }
    @GetMapping(value = "/interchange/har/export", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ByteArrayResource> exportHar(Authentication authentication, @PathVariable String projectId, @RequestParam(required = false) String catalogId, @RequestParam(required = false) String baseUrl) throws Exception {
        byte[] body = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(interchange.exportHar(principal(authentication), projectId, catalogId, baseUrl));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).contentLength(body.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("testhub-api-archive.har", StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(body));
    }
    @GetMapping("/definitions/{apiId}/interchange/curl") public ApiResponse<CurlExport> exportCurl(Authentication authentication, @PathVariable String projectId, @PathVariable String apiId, @RequestParam(required = false) String baseUrl) { return ApiResponse.of(new CurlExport(interchange.exportCurl(principal(authentication), projectId, apiId, baseUrl))); }
    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }

    public record CatalogRequest(@NotBlank @Size(max = 160) String name, String parentId, int sortOrder) { ApiDesignService.CatalogInput toInput() { return new ApiDesignService.CatalogInput(name, parentId, sortOrder); } }
    public record ParameterRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 40) String type, boolean required, @Size(max = 1000) String example, @Size(max = 2000) String description) { ApiDesignService.ParameterInput toInput() { return new ApiDesignService.ParameterInput(name, type, required, example, description); } }
    public record DefinitionRequest(@NotBlank @Size(max = 26) String catalogId, @NotBlank @Size(max = 12) String method, @NotBlank @Size(max = 700) String path, @NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 40) String versionName, @Size(max = 500) String summary, @Size(max = 20_000) String description, List<ParameterRequest> headers, List<ParameterRequest> query, List<ParameterRequest> pathParameters, @Size(max = 100_000) String requestBody, @Size(max = 100_000) String responseBody, Map<String, Object> exampleResponse) {
        ApiDesignService.DefinitionInput toInput() { return new ApiDesignService.DefinitionInput(catalogId, method, path, name, versionName, summary, description, map(headers), map(query), map(pathParameters), requestBody, responseBody, exampleResponse); }
    }
    public record VersionRequest(@NotBlank @Size(max = 40) String versionName, @Size(max = 500) String summary, @Size(max = 20_000) String description, List<ParameterRequest> headers, List<ParameterRequest> query, List<ParameterRequest> pathParameters, @Size(max = 100_000) String requestBody, @Size(max = 100_000) String responseBody, Map<String, Object> exampleResponse) {
        ApiDesignService.VersionInput toInput() { return new ApiDesignService.VersionInput(versionName, summary, description, map(headers), map(query), map(pathParameters), requestBody, responseBody, exampleResponse); }
    }
    public record OpenApiImportRequest(@NotBlank @Size(max = 26) String catalogId, @NotBlank @Size(max = 2_000_000) String document) { }
    public record CurlImportRequest(@NotBlank @Size(max = 26) String catalogId, @Size(max = 200) String name, @NotBlank @Size(max = 100_000) String command) { }
    public record PostmanImportRequest(@NotBlank @Size(max = 26) String catalogId, @NotBlank @Size(max = 2_000_000) String document) { }
    public record HarImportRequest(@NotBlank @Size(max = 26) String catalogId, @NotBlank @Size(max = 2_000_000) String document) { }
    public record CurlExport(String command) { }
    private static List<ApiDesignService.ParameterInput> map(List<ParameterRequest> values) { return values == null ? List.of() : values.stream().map(ParameterRequest::toInput).toList(); }
}
