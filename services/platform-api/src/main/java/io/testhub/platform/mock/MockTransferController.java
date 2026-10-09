package io.testhub.platform.mock;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.shared.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/mock/transfer")
public class MockTransferController {
    private final MockTransferService transfer;

    public MockTransferController(MockTransferService transfer) { this.transfer = transfer; }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.capabilities((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }

    @GetMapping
    public ApiResponse<MockTransferService.Snapshot> export(Authentication authentication, @PathVariable String projectId) {
        return ApiResponse.of(transfer.export((AuthenticatedPrincipal) authentication.getPrincipal(), projectId));
    }

    @PostMapping
    public ApiResponse<MockTransferService.Receipt> transfer(Authentication authentication, @PathVariable String projectId,
                                                             @RequestBody byte[] body) {
        return ApiResponse.of(transfer.transfer((AuthenticatedPrincipal) authentication.getPrincipal(), projectId, transfer.decode(body)));
    }
}
