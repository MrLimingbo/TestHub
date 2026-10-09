package io.testhub.platform.account;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessRepository;
import io.testhub.platform.parameter.ProjectEnvironmentRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "testhub.bootstrap.demo-enabled", havingValue = "true", matchIfMissing = true)
public class BootstrapAccountConfiguration {
    @Bean
    ApplicationRunner bootstrapLocalWorkspace(UserAccountRepository accounts, AccessRepository access,
                                              ProjectEnvironmentRepository environments,
                                              PasswordEncoder passwordEncoder) {
        return ignored -> {
            UserAccount administrator = ensureAccount(accounts, passwordEncoder, "admin@testhub.local", "系统管理员");
            UserAccount lead = ensureAccount(accounts, passwordEncoder, "lead@testhub.local", "测试负责人");
            UserAccount tester = ensureAccount(accounts, passwordEncoder, "tester@testhub.local", "测试工程师");
            var organization = access.findOrganizationByCode("nebula")
                    .orElseGet(() -> {
                        String id = UlidCreator.getUlid().toString();
                        access.insertOrganization(id, "星云科技", "nebula");
                        return access.findOrganizationByCode("nebula").orElseThrow();
                    });
            var project = access.findProjectByCode(organization.id(), "trade-v3")
                    .orElseGet(() -> {
                        String id = UlidCreator.getUlid().toString();
                        access.insertProject(id, organization.id(), "交易中台 V3", "trade-v3");
                        return access.findProjectByCode(organization.id(), "trade-v3").orElseThrow();
                    });
            if (environments.count(project.id()) == 0) {
                environments.insert(UlidCreator.getUlid().toString(), organization.id(), project.id(),
                        "默认测试环境", "test", "", "项目创建时生成的默认环境",
                        true, true, administrator.id());
            }
            ensureMembership(access, administrator, organization.id(), null, "SYSTEM_ADMIN");
            ensureMembership(access, lead, organization.id(), project.id(), "TEST_LEAD");
            ensureMembership(access, tester, organization.id(), project.id(), "TESTER");
        };
    }

    private UserAccount ensureAccount(UserAccountRepository accounts, PasswordEncoder passwordEncoder, String email,
                                      String displayName) {
        return accounts.findByEmail(email).orElseGet(() -> {
            UserAccount account = new UserAccount(UlidCreator.getUlid().toString(), email.substring(0, email.indexOf('@')),
                    email, displayName,
                    passwordEncoder.encode("TestHub2026!"), true);
            accounts.insert(account);
            return account;
        });
    }

    private void ensureMembership(AccessRepository access, UserAccount account, String organizationId, String projectId,
                                  String roleCode) {
        boolean exists = access.findRoles(account.id(), organizationId, projectId).contains(roleCode);
        if (!exists) {
            access.insertMembership(UlidCreator.getUlid().toString(), account.id(), organizationId, projectId, roleCode);
        }
    }
}
