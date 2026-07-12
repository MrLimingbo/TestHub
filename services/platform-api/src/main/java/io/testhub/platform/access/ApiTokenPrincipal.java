package io.testhub.platform.access;

import io.testhub.platform.account.UserAccount;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public record ApiTokenPrincipal(UserAccount account, String projectId, List<String> scopes)
        implements AuthenticatedPrincipal, Serializable {
    private static final long serialVersionUID = 1L;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return scopes.stream().map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope)).toList();
    }

    @Override
    public String getPassword() {
        return "";
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

    @Override
    public String tokenProjectId() {
        return projectId;
    }
}
