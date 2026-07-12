package io.testhub.platform.account;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserAccountRepository {
    private final JdbcClient jdbc;

    public UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("""
                SELECT id, email, display_name, password_hash, enabled
                FROM user_account WHERE email = :email
                """)
                .param("email", email.toLowerCase())
                .query((rs, rowNumber) -> new UserAccount(
                        rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getBoolean("enabled")))
                .optional();
    }

    public Optional<UserAccount> findById(String id) {
        return jdbc.sql("""
                SELECT id, email, display_name, password_hash, enabled
                FROM user_account WHERE id = :id
                """)
                .param("id", id)
                .query((rs, rowNumber) -> new UserAccount(
                        rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("password_hash"), rs.getBoolean("enabled")))
                .optional();
    }

    public boolean hasAnyUser() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_account)")
                .query(Boolean.class)
                .single();
    }

    public void insert(UserAccount account) {
        jdbc.sql("""
                INSERT INTO user_account (id, email, display_name, password_hash, enabled)
                VALUES (:id, :email, :displayName, :passwordHash, :enabled)
                """)
                .param("id", account.id())
                .param("email", account.email())
                .param("displayName", account.displayName())
                .param("passwordHash", account.passwordHash())
                .param("enabled", account.enabled())
                .update();
    }
}
