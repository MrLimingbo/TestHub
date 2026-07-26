package io.testhub.platform.mock;

import io.testhub.platform.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mock-shares")
public class MockShareController {
    private final MockService mocks;

    public MockShareController(MockService mocks) {
        this.mocks = mocks;
    }

    @GetMapping("/{shareKey}")
    public ApiResponse<MockShareDocument> share(@PathVariable String shareKey) {
        return ApiResponse.of(mocks.publicShare(shareKey));
    }
}
