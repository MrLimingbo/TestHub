package io.testhub.platform.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserAccountRepository {
    private final JdbcClient jdbc;

    public UserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("""
                SELECT id, account, email, display_name, password_hash, enabled, security_version
                FROM user_account WHERE LOWER(email) = :email AND deleted_at IS NULL
                """)
                .param("email", email.toLowerCase())
                .query((rs, rowNumber) -> mapUser(rs))
                .optional();
    }

    public Optional<UserAccount> findByAccountOrEmail(String value) {
        List<UserAccount> matches = jdbc.sql("""
                SELECT id, account, email, display_name, password_hash, enabled, security_version
                FROM user_account
                WHERE deleted_at IS NULL AND (LOWER(account) = :value OR LOWER(email) = :value)
                LIMIT 2
                """)
                .param("value", value.toLowerCase(Locale.ROOT))
                .query((rs, rowNumber) -> mapUser(rs))
                .list();
        // Legacy cross-field collisions must never authenticate an arbitrary matching account.
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public Optional<UserAccount> findById(String id) {
        return jdbc.sql("""
                SELECT id, account, email, display_name, password_hash, enabled, security_version
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

    @Transactional
    public void insert(UserAccount account) {
        insert(account, null);
    }

    @Transactional
    public void insert(UserAccount account, String phone) {
        requireAvailableIdentifiers(account.id(), account.account(), account.email());
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

    public List<AccountOption> findWorkspaceAccountOptions(String userId) {
        return jdbc.sql("""
                SELECT u.id, u.account, u.display_name, u.email
                FROM user_account u
                WHERE u.deleted_at IS NULL AND u.enabled = TRUE
                  AND (u.id = :userId OR EXISTS (
                      SELECT 1 FROM membership own
                      JOIN membership colleague ON colleague.organization_id = own.organization_id
                      WHERE own.user_id = :userId AND colleague.user_id = u.id
                  ))
                ORDER BY u.account
                """).param("userId", userId)
                .query((rs, rowNumber) -> new AccountOption(rs.getString("id"), rs.getString("account"),
                        rs.getString("display_name"), rs.getString("email"))).list();
    }

    @Transactional
    public void update(String id, String account, String displayName, String email, String phone, boolean enabled) {
        requireAvailableIdentifiers(id, account, email);
        jdbc.sql("""
                UPDATE user_account
                SET security_version = security_version + CASE WHEN enabled <> :enabled THEN 1 ELSE 0 END,
                    account = :account, display_name = :displayName, email = :email, phone = :phone, enabled = :enabled
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
                UPDATE user_account SET security_version = security_version + 1, password_hash = :passwordHash
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .param("passwordHash", passwordHash)
                .update();
    }

    @Transactional
    public void softDelete(String id) {
        lockIdentityChanges();
        jdbc.sql("""
                UPDATE user_account
                SET security_version = security_version + 1, enabled = FALSE, deleted_at = CURRENT_TIMESTAMP(3),
                    account = CONCAT(LEFT(account, 119), '-deleted-', LOWER(id)),
                    email = CONCAT(LOWER(id), '@deleted.testhub.local')
                WHERE id = :id AND deleted_at IS NULL
                """)
                .param("id", id)
                .update();
    }

    private void lockIdentityChanges() {
        jdbc.sql("SELECT singleton_id FROM account_identity_guard WHERE singleton_id = 1 FOR UPDATE")
                .query(Integer.class).single();
    }

    private void requireAvailableIdentifiers(String id, String account, String email) {
        // One database lock covers both columns and also works across application instances.
        lockIdentityChanges();
        var conflicts = jdbc.sql("""
                SELECT id FROM user_account
                WHERE deleted_at IS NULL AND id <> :id
                  AND (LOWER(account) IN (:account, :email) OR LOWER(email) IN (:account, :email))
                FOR UPDATE
                """)
                .param("id", id)
                .param("account", account.toLowerCase(Locale.ROOT))
                .param("email", email.toLowerCase(Locale.ROOT))
                .query(String.class).list();
        if (!conflicts.isEmpty()) throw new DuplicateKeyException("账号或邮箱已存在");
    }

    private UserAccount mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserAccount(rs.getString("id"), rs.getString("account"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getLong("security_version"));
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
