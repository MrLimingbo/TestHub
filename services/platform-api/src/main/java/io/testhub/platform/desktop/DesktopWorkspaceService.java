package io.testhub.platform.desktop;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.account.UserAccount;
import io.testhub.platform.account.UserAccountRepository;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("desktop")
public class DesktopWorkspaceService {
    private final JdbcClient jdbc;
    private final UserAccountRepository accounts;
    private final AccessRepository access;
    private final ProjectEnvironmentRepository environments;
    private final PasswordEncoder passwords;
    public DesktopWorkspaceService(JdbcClient jdbc, UserAccountRepository accounts, AccessRepository access,
                                   ProjectEnvironmentRepository environments, PasswordEncoder passwords) {
        this.jdbc = jdbc; this.accounts = accounts; this.access = access; this.environments = environments; this.passwords = passwords;
    }
    @Transactional
    public UserAccount owner() {
        // Lock persists until commit, including two simultaneous native session bootstrap requests.
        String existing = jdbc.sql("SELECT user_id FROM desktop_workspace_identity WHERE singleton_id=1 FOR UPDATE")
                .query((row, index) -> row.getString("user_id")).optional().orElse(null);
        if (existing != null) return accounts.findById(existing).filter(UserAccount::enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "本地工作区身份已停用，请恢复工作区备份或使用已有管理员账号处理"));
        String userId = UlidCreator.getUlid().toString();
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        var account = new UserAccount(userId, "local-" + userId.toLowerCase(java.util.Locale.ROOT),
                userId.toLowerCase(java.util.Locale.ROOT) + "@workspace.testhub.invalid", "本地用户",
                passwords.encode(HexFormat.of().formatHex(random)), true);
        accounts.insert(account);
        String organizationId = UlidCreator.getUlid().toString(), projectId = UlidCreator.getUlid().toString();
        access.insertOrganization(organizationId, "本地工作区", "local-" + organizationId.toLowerCase(java.util.Locale.ROOT));
        access.insertProject(projectId, organizationId, "我的项目", "my-project", "保存在这台电脑上的项目", userId);
        // System settings belong to this private installation. Online registration never grants this role.
        access.insertMembership(UlidCreator.getUlid().toString(), userId, organizationId, null, "SYSTEM_ADMIN");
        access.insertMembership(UlidCreator.getUlid().toString(), userId, organizationId, null, "ORG_ADMIN");
        access.insertMembership(UlidCreator.getUlid().toString(), userId, organizationId, projectId, "PROJECT_ADMIN");
        environments.insert(UlidCreator.getUlid().toString(), organizationId, projectId, "默认测试环境", "test", "",
                "本地工作区默认环境", true, true, userId);
        jdbc.sql("UPDATE desktop_workspace_identity SET user_id=? WHERE singleton_id=1").param(userId).update();
        return account;
    }
}
