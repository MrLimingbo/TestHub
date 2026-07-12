package io.testhub.platform.account;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final UserAccountRepository accounts;

    public AccountUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return accounts.findByEmail(username)
                .map(PlatformUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
    }
}
