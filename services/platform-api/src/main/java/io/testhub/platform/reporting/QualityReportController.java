package io.testhub.platform.reporting;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/quality")
public class QualityReportController {
    private final QualityReportService reports;

    public QualityReportController(QualityReportService reports) {
        this.reports = reports;
    }

    @GetMapping
    public ApiResponse<QualityReport> get(Authentication authentication,
                                          @RequestParam(required = false) List<String> projectIds,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                          LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                          LocalDate to) {
        return ApiResponse.of(reports.get(principal(authentication), projectIds, from, to));
    }

    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(Authentication authentication,
                                                     @RequestParam(required = false) List<String> projectIds,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                                     LocalDate to) {
        QualityReportService.CsvExport export = reports.export(principal(authentication), projectIds, from, to);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentLength(export.content().length);
        headers.setContentDisposition(ContentDisposition.attachment().filename(export.filename()).build());
        return ResponseEntity.ok().headers(headers).body(new ByteArrayResource(export.content()));
    }

    private AuthenticatedPrincipal principal(Authentication authentication) {
        return (AuthenticatedPrincipal) authentication.getPrincipal();
    }
}
