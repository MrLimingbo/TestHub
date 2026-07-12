package io.testhub.platform.account;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import io.testhub.platform.security.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record PlatformUserPrincipal(UserAccount account) implements AuthenticatedPrincipal, Serializable {
    private static final long serialVersionUID = 1L;
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return account.passwordHash();
    }

    @Override
    public String getUsername() {
        return account.email();
    }

    @Override
    public boolean isEnabled() {
        return account.enabled();
    }

    @Override
    public String userId() {
        return account.id();
    }
}
