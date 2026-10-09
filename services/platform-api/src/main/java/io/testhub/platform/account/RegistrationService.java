package io.testhub.platform.account;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistrationService {
    private final UserAccountRepository accounts;
    private final AccessRepository access;
    private final ProjectEnvironmentRepository environments;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final boolean enabled;

    public RegistrationService(UserAccountRepository accounts, AccessRepository access,
                               ProjectEnvironmentRepository environments, PasswordEncoder passwords,
                               AuditService audit,
                               @Value("${testhub.registration.enabled:false}") boolean enabled) {
        this.accounts = accounts;
        this.access = access;
        this.environments = environments;
        this.passwords = passwords;
        this.audit = audit;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @Transactional
    public UserAccount register(String account, String email, String displayName, String password) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前服务未开放注册");
        // Keep account identifiers disjoint from email identifiers to prevent ambiguous logins.
        String normalizedAccount = account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
        if (!normalizedAccount.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号需为 3 到 64 位字母、数字、点、下划线或短横线");
        }
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 个字符，且不能超过 72 字节");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        String name = displayName == null || displayName.isBlank() ? normalizedAccount : displayName.trim();
        if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "显示名称最多 80 个字符，不能包含控制字符");
        }
        if (accounts.findByAccountOrEmail(normalizedAccount).isPresent()
                || accounts.findByAccountOrEmail(normalizedEmail).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号或邮箱已存在");
        }
        UserAccount created = new UserAccount(UlidCreator.getUlid().toString(), normalizedAccount,
                normalizedEmail, name, passwords.encode(password), true);
        try {
            accounts.insert(created);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号或邮箱已存在");
        }
        String organizationId = UlidCreator.getUlid().toString();
        String projectId = UlidCreator.getUlid().toString();
        access.insertOrganization(organizationId, name + "的工作区", "personal-" + created.id().toLowerCase(Locale.ROOT));
        access.insertProject(projectId, organizationId, "我的项目", "my-project", "个人工作区的默认项目", created.id());
        access.insertMembership(UlidCreator.getUlid().toString(), created.id(), organizationId, null, "ORG_ADMIN");
        access.insertMembership(UlidCreator.getUlid().toString(), created.id(), organizationId, projectId, "PROJECT_ADMIN");
        environments.insert(UlidCreator.getUlid().toString(), organizationId, projectId, "默认测试环境", "test", "",
                "注册时生成的默认环境", true, true, created.id());
        audit.record(new PlatformUserPrincipal(created), organizationId, projectId,
                "ACCOUNT_REGISTERED", "USER_ACCOUNT", created.id(), Map.of("source", "SELF_REGISTRATION"));
        return created;
    }
}
