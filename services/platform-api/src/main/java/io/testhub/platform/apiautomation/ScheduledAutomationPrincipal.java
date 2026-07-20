package io.testhub.platform.apiautomation;

import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Principal synthesized only by a persisted, enabled schedule after Quartz has selected its fixed job key. */
public record ScheduledAutomationPrincipal(String userId, String projectId, String scheduleId)
        implements AuthenticatedPrincipal {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("SCOPE_run:execute"));
    }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return "schedule:" + scheduleId; }
    @Override public boolean isEnabled() { return true; }
    @Override public String tokenProjectId() { return projectId; }
    @Override public String automationScheduleId() { return scheduleId; }
}
