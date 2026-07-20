package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Principal synthesized only after an inbound webhook HMAC and nonce have been verified. */
public record WebhookAutomationPrincipal(String userId, String projectId, String triggerId) implements AuthenticatedPrincipal {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("SCOPE_run:execute"));
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return "webhook:" + triggerId; }
    @Override public boolean isEnabled() { return true; }
    @Override public String tokenProjectId() { return projectId; }
    @Override public String webhookTriggerId() { return triggerId; }
}
