package io.testhub.platform.shared.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Also catches dependency failures while Spring Session loads or saves the security context. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DependencyUnavailableFilter extends OncePerRequestFilter {
    private final ObjectMapper json;
    public DependencyUnavailableFilter(ObjectMapper json) { this.json = json; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException | RuntimeException failure) {
            if (!DependencyFailures.isUnavailable(failure) || response.isCommitted()) throw failure;
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), new ApiError("DEPENDENCY_UNAVAILABLE", "服务依赖暂时不可用，请稍后重试",
                    (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE), Map.of()));
        }
    }
}
