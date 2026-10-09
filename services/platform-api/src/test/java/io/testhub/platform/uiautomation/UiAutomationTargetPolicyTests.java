package io.testhub.platform.uiautomation;

import io.testhub.platform.config.SystemConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UiAutomationTargetPolicyTests {
    @Test void desktopUsesOnlyNativeApprovedExactOriginsIncludingLocalIpTargets() {
        var configs = mock(SystemConfigRepository.class);
        var environment = new MockEnvironment().withProperty("testhub.desktop.allowed-origins", "http://localhost:9000,http://127.0.0.1:9001,https://example.test");
        environment.setActiveProfiles("desktop");
        var policy = new UiAutomationTargetPolicy(configs, environment);
        assertEquals("http://localhost:9000/page", policy.normalizeAndAssertAllowed("http://localhost:9000/page"));
        assertEquals("http://127.0.0.1:9001/page", policy.normalizeAndAssertAllowed("http://127.0.0.1:9001/page"));
        assertThrows(ResponseStatusException.class, () -> policy.normalizeAndAssertAllowed("http://localhost:9002/"));
        assertThrows(ResponseStatusException.class, () -> policy.normalizeAndAssertAllowed("http://user@localhost:9000/"));
        assertEquals("https://example.test:443/page", policy.normalizeAndAssertAllowed("https://example.test:443/page"));
        verifyNoInteractions(configs);
    }
    @Test void enterpriseStillRequiresSystemConfigurationAndRejectsIpLiterals() {
        var configs = mock(SystemConfigRepository.class);
        when(configs.findNonSecretValue(UiAutomationTargetPolicy.ALLOWED_ORIGINS_CONFIG)).thenReturn(Optional.of("https://example.test"));
        var policy = new UiAutomationTargetPolicy(configs, new MockEnvironment().withProperty("testhub.desktop.allowed-origins", "http://localhost:9000"));
        assertEquals("https://example.test/login", policy.normalizeAndAssertAllowed("https://example.test/login"));
        assertThrows(ResponseStatusException.class, () -> policy.normalizeAndAssertAllowed("http://127.0.0.1/"));
        assertThrows(ResponseStatusException.class, () -> policy.normalizeAndAssertAllowed("http://localhost:9000/"));
    }
}
