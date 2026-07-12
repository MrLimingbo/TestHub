package io.testhub.platform.security;

import org.springframework.security.core.userdetails.UserDetails;

public interface AuthenticatedPrincipal extends UserDetails {
    String userId();

    default String tokenProjectId() {
        return null;
    }
}
