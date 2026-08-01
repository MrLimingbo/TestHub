package io.testhub.platform.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
                SELECT id, account, email, display_name, password_hash, enabled
                FROM user_account WHERE LOWER(email) = :email AND deleted_at IS NULL
                """)
                .param("email", email.toLowerCase())
                .query((rs, rowNumber) -> mapUser(rs))
                .optional();
    }

    public Optional<UserAccount> findByAccountOrEmail(String value) {
        return jdbc.sql("""
                SELECT id, account, email, display_name, password_hash, enabled
                FROM user_account
                WHERE deleted_at IS NULL AND (LOWER(account) = :value OR LOWER(email) = :value)
                """)
                .param("value", value.toLowerCase())
                .query((rs, rowNumber) -> mapUser(rs))
                .optional();
    }

    public Optional<UserAccount> findById(String id) {
        return jdbc.sql("""
                SELECT id, account, email, display_name, password_hash, enabled
                FROM user_account WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, rowNumber) -> mapUser(rs))
                .optional();
    }

    public boolean hasAnyUser() {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM user_account WHERE deleted_at IS NULL)")
                .query(Boolean.class)
                .single();
    }

    public void insert(UserAccount account) {
        insert(account, null);
    }

    public void insert(UserAccount account, String phone) {
        jdbc.sql("""
                INSERT INTO user_account (id, account, email, display_name, phone, password_hash, enabled)
                VALUES (:id, :account, :email, :displayName, :phone, :passwordHash, :enabled)
                """)
                .param("id", account.id())
                .param("account", account.account())
                .param("email", account.email())
                .param("displayName", account.displayName())
                .param("phone", phone)
                .param("passwordHash", account.passwordHash())
                .param("enabled", account.enabled())
                .update();
    }

    public List<ManagedAccount> findPage(String keyword, int offset, int limit) {
        String search = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase() + "%";
        return jdbc.sql("""
                SELECT id, account, display_name, email, phone, enabled, created_at, updated_at
                FROM user_account
                WHERE deleted_at IS NULL
                  AND (:keyword IS NULL OR LOWER(account) LIKE :keyword OR LOWER(display_name) LIKE :keyword
                       OR LOWER(email) LIKE :keyword OR LOWER(COALESCE(phone, '')) LIKE :keyword)
                ORDER BY created_at DESC, account
                LIMIT :limit OFFSET :offset
                """)
                .param("keyword", search)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNumber) -> new ManagedAccount(
                        rs.getString("id"), rs.getString("account"), rs.getString("display_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getBoolean("enabled"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))))
                .list();
    }

    public long count(String keyword) {
        String search = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase() + "%";
        return jdbc.sql("""
                SELECT COUNT(*)
                FROM user_account
                WHERE deleted_at IS NULL
                  AND (:keyword IS NULL OR LOWER(account) LIKE :keyword OR LOWER(display_name) LIKE :keyword
                       OR LOWER(email) LIKE :keyword OR LOWER(COALESCE(phone, '')) LIKE :keyword)
                """)
                .param("keyword", search)
                .query(Long.class)
                .single();
    }

    public List<AccountOption> findEnabledOptions() {
        return jdbc.sql("""
                SELECT id, account, display_name, email
                FROM user_account
                WHERE deleted_at IS NULL AND enabled = TRUE
                ORDER BY account
                """)
                .query((rs, rowNumber) -> new AccountOption(
                        rs.getString("id"), rs.getString("account"), rs.getString("display_name"),
                        rs.getString("email")))
                .list();
    }

    public Optional<ManagedAccount> findManagedById(String id) {
        return jdbc.sql("""
                SELECT id, account, display_name, email, phone, enabled, created_at, updated_at
                FROM user_account WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .query((rs, rowNumber) -> new ManagedAccount(
                        rs.getString("id"), rs.getString("account"), rs.getString("display_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getBoolean("enabled"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))))
                .optional();
    }

    public void update(String id, String account, String displayName, String email, String phone, boolean enabled) {
        jdbc.sql("""
                UPDATE user_account
                SET account = :account, display_name = :displayName, email = :email, phone = :phone, enabled = :enabled
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("account", account)
                .param("displayName", displayName)
                .param("email", email)
                .param("phone", phone)
                .param("enabled", enabled)
                .update();
    }

    public void updatePassword(String id, String passwordHash) {
        jdbc.sql("""
                UPDATE user_account SET password_hash = :passwordHash
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("passwordHash", passwordHash)
                .update();
    }

    public void softDelete(String id) {
        jdbc.sql("""
                UPDATE user_account
                SET enabled = FALSE, deleted_at = CURRENT_TIMESTAMP(3),
                    account = CONCAT(LEFT(account, 119), '-deleted-', LOWER(id)),
                    email = CONCAT(LOWER(id), '@deleted.testhub.local')
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .update();
    }

    private UserAccount mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAccount(rs.getString("id"), rs.getString("account"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("enabled"));
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record ManagedAccount(String id, String account, String displayName, String email, String phone,
                                 boolean enabled, Instant createdAt, Instant updatedAt) {
    }

    public record AccountOption(String id, String account, String displayName, String email) {
    }
}
