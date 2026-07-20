package io.testhub.platform.security;

import org.springframework.security.core.userdetails.UserDetails;

public interface AuthenticatedPrincipal extends UserDetails {
    String userId();

    default String tokenProjectId() {
        return null;
    }

    /** Non-null only for a verified inbound automation webhook. */
    default String webhookTriggerId() {
        return null;
    }

    /** Non-null only for a Quartz-fired, persisted automation schedule. */
    default String automationScheduleId() {
        return null;
    }
}
