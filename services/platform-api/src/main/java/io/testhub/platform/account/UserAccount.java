package io.testhub.platform.account;

import java.io.Serializable;

public record UserAccount(
        String id,
        String account,
        String email,
        String displayName,
        String passwordHash,
        boolean enabled
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
