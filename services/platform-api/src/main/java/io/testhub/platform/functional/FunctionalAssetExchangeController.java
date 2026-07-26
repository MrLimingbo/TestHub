package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/functional/assets")
public class FunctionalAssetExchangeController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final FunctionalAssetExchangeService exchange;

    public FunctionalAssetExchangeController(FunctionalAssetExchangeService exchange) { this.exchange = exchange; }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(Authentication authentication, @PathVariable String projectId,
                                                     @RequestParam FunctionalAssetExchangeService.AssetKind type,
                                                     @RequestParam(defaultValue = "false") boolean template) {
        FunctionalAssetExchangeService.ExportWorkbook workbook = exchange.export(principal(authentication), projectId, type, template);
        return ResponseEntity.ok().contentType(XLSX).contentLength(workbook.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(workbook.filename(), StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(workbook.bytes()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<FunctionalAssetExchangeService.ImportResult> importWorkbook(Authentication authentication, @PathVariable String projectId,
                                                                                    @RequestParam FunctionalAssetExchangeService.AssetKind type,
                                                                                    @RequestParam("file") MultipartFile file) {
        return ApiResponse.of(exchange.importWorkbook(principal(authentication), projectId, type, file));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) { return (AuthenticatedPrincipal) authentication.getPrincipal(); }
}
