package io.testhub.platform.shared.api;

import static org.junit.jupiter.api.Assertions.*;
import io.lettuce.core.RedisException;
import java.net.SocketException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

class P1DependencyFailureTests {
    @Test void wrappedRedisConnectionResetIsUnavailable() {
        assertTrue(DependencyFailures.isUnavailable(new RedisSystemException("private-fixture", new RedisException(new SocketException("Connection reset")))));
    }
    @Test void redisCommandTimeoutIsUnavailable() {
        assertTrue(DependencyFailures.isUnavailable(new RedisSystemException("private-fixture", new io.lettuce.core.RedisCommandTimeoutException("private-fixture"))));
    }
    @Test void jdbcConnectionFailureIsUnavailable() {
        assertTrue(DependencyFailures.isUnavailable(new java.sql.SQLTransientConnectionException("private-fixture")));
    }
    @Test void unrelatedSocketErrorsAreNotBroadlyReclassified() {
        assertFalse(DependencyFailures.isUnavailable(new IllegalStateException(new SocketException("private-fixture"))));
    }
    @Test void redisProgrammingErrorsAreNotDisguisedAsOutages() {
        assertFalse(DependencyFailures.isUnavailable(new RedisSystemException("private-fixture", new io.lettuce.core.RedisCommandExecutionException("WRONGTYPE"))));
    }
    @Test void sessionPhaseFailureReturnsSharedSanitized503WithoutForwardingTheBrokenSession() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/auth/me"); var response = new MockHttpServletResponse();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "p1-request");
        new DependencyUnavailableFilter(new ObjectMapper()).doFilter(request, response, (req, res) -> {
            throw new RedisSystemException("private-fixture", new RedisException(new SocketException("Connection reset")));
        });
        assertEquals(503, response.getStatus()); assertTrue(response.getContentAsString().contains("DEPENDENCY_UNAVAILABLE"));
        assertTrue(response.getContentAsString().contains("p1-request")); assertFalse(response.getContentAsString().contains("private-fixture"));
    }
}
