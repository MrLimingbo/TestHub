package io.testhub.platform.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.stereotype.Component;

@Component
public class MockAwareFormContentFilter extends OrderedFormContentFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Mock conditions compare the original body; form parsing would consume PUT/PATCH/DELETE content.
        return request.getRequestURI().startsWith(request.getContextPath() + "/api/v1/mock/");
    }
}
