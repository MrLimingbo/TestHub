package io.testhub.platform.account;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountManagementService {
    public static final String DEFAULT_PASSWORD = "admin";
    private final UserAccountRepository accounts;
    private final AccessRepository access;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public AccountManagementService(UserAccountRepository accounts, AccessRepository access,
                                    PasswordEncoder passwordEncoder, AuditService audit) {
        this.accounts = accounts;
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    public AccountPage list(AuthenticatedPrincipal principal, String keyword, Integer page, Integer pageSize) {
        requireSystemAdmin(principal);
        int normalizedPage = Math.min(Math.max(page == null ? 1 : page, 1), 100_000);
        int normalizedPageSize = Math.min(Math.max(pageSize == null ? 10 : pageSize, 1), 100);
        long total = accounts.count(keyword);
        int offset = (normalizedPage - 1) * normalizedPageSize;
        return new AccountPage(accounts.findPage(keyword, offset, normalizedPageSize), total,
                normalizedPage, normalizedPageSize);
    }

    public List<UserAccountRepository.AccountOption> options(AuthenticatedPrincipal principal) {
        if (principal.tokenProjectId() != null) {
            throw new AccessDeniedException("项目令牌不能查询系统账号");
        }
        return accounts.findEnabledOptions();
    }

    @Transactional
    public UserAccountRepository.ManagedAccount create(AuthenticatedPrincipal principal, String account,
                                                       String displayName, String email, String phone, boolean enabled) {
        requireSystemAdmin(principal);
        UserAccount created = createAccount(account, displayName, email, phone, enabled);
        audit.record(principal, null, null, "ACCOUNT_CREATED", "USER_ACCOUNT", created.id(),
                java.util.Map.of("account", created.account()));
        return accounts.findManagedById(created.id()).orElseThrow();
    }

    @Transactional
    public UserAccountRepository.ManagedAccount update(AuthenticatedPrincipal principal, String id, String account,
                                                       String displayName, String email, String phone, boolean enabled) {
        requireSystemAdmin(principal);
        accounts.findManagedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        if (principal.userId().equals(id) && !enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用当前登录账号");
        }
        String normalizedAccount = normalizeAccount(account);
        try {
            accounts.update(id, normalizedAccount, normalizeDisplayName(displayName, normalizedAccount),
                    normalizeEmail(email, normalizedAccount), normalizeOptional(phone), enabled);
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号或邮箱已存在");
        }
        audit.record(principal, null, null, "ACCOUNT_UPDATED", "USER_ACCOUNT", id,
                java.util.Map.of("account", normalizedAccount, "enabled", enabled));
        return accounts.findManagedById(id).orElseThrow();
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String id) {
        requireSystemAdmin(principal);
        UserAccountRepository.ManagedAccount target = accounts.findManagedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        if (principal.userId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能删除当前登录账号");
        }
        accounts.softDelete(id);
        audit.record(principal, null, null, "ACCOUNT_DELETED", "USER_ACCOUNT", id,
                java.util.Map.of("account", target.account()));
    }

    @Transactional
    public void updatePassword(AuthenticatedPrincipal principal, String id, String password) {
        requireSystemAdmin(principal);
        UserAccountRepository.ManagedAccount target = accounts.findManagedById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        if (password == null || password.length() < 5 || password.length() > 72
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码长度应为 5 到 72 个字符，且不能超过 72 字节");
        }
        accounts.updatePassword(id, passwordEncoder.encode(password));
        audit.record(principal, null, null, "ACCOUNT_PASSWORD_CHANGED", "USER_ACCOUNT", id,
                java.util.Map.of("account", target.account()));
    }

    @Transactional
    public UserAccount resolveOrCreateForProjectMember(AuthenticatedPrincipal principal, ProjectSummary project,
                                                       String account) {
        String normalized = normalizeAccount(account);
        return accounts.findByAccountOrEmail(normalized).orElseGet(() -> {
            UserAccount created = createAccount(normalized, normalized, null, null, true);
            audit.record(principal, project.organizationId(), project.id(), "ACCOUNT_CREATED", "USER_ACCOUNT",
                    created.id(), java.util.Map.of("account", created.account(), "source", "PROJECT_MEMBER"));
            return created;
        });
    }

    private UserAccount createAccount(String account, String displayName, String email, String phone, boolean enabled) {
        String normalizedAccount = normalizeAccount(account);
        UserAccount created = new UserAccount(UlidCreator.getUlid().toString(), normalizedAccount,
                normalizeEmail(email, normalizedAccount), normalizeDisplayName(displayName, normalizedAccount),
                passwordEncoder.encode(DEFAULT_PASSWORD), enabled);
        try {
            accounts.insert(created, normalizeOptional(phone));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号或邮箱已存在");
        }
        return created;
    }

    private void requireSystemAdmin(AuthenticatedPrincipal principal) {
        if (principal.tokenProjectId() != null || !access.hasRole(principal.userId(), "SYSTEM_ADMIN")) {
            throw new AccessDeniedException("仅系统管理员可访问");
        }
    }

    private String normalizeAccount(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号不能为空");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160 || normalized.chars().anyMatch(Character::isWhitespace)
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号不能包含空格或控制字符，且最多 160 个字符");
        }
        return normalized;
    }

    private String normalizeDisplayName(String value, String account) {
        String normalized = value == null || value.isBlank() ? account : value.trim();
        if (normalized.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "姓名最多 80 个字符");
        }
        return normalized;
    }

    private String normalizeEmail(String value, String account) {
        String normalized = value == null || value.isBlank()
                ? (account.contains("@") ? account : account + "@testhub.local")
                : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 190 || !normalized.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public record AccountPage(List<UserAccountRepository.ManagedAccount> items, long total, int page, int pageSize) {
    }
}
