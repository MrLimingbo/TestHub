package io.testhub.platform.reporting;

import io.testhub.platform.security.AuthenticatedPrincipal;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;

/** Synthetic identity used only after Quartz has selected a stored quality report schedule. */
public record ScheduledQualityReportPrincipal(String userId, String scheduleId) implements AuthenticatedPrincipal {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
    @Override public String getPassword() { return ""; }
    @Override public String getUsername() { return "quality-report-schedule:" + scheduleId; }
    @Override public boolean isEnabled() { return true; }
}
