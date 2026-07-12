package io.testhub.platform.parameter;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnvironmentSecretService {
    private static final Set<String> SECRET_WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD");
    private final ProjectParameterRepository parameters;
    private final AccessService access;
    private final SecretCipher cipher;
    private final AuditService audit;

    public EnvironmentSecretService(ProjectParameterRepository parameters, AccessService access, SecretCipher cipher,
                                    AuditService audit) {
        this.parameters = parameters;
        this.access = access;
        this.cipher = cipher;
        this.audit = audit;
    }

    public SecretCollection list(AuthenticatedPrincipal principal, String projectId, String environmentCode) {
        access.requireProjectRead(principal, projectId);
        String normalizedEnvironment = environmentCode == null || environmentCode.isBlank()
                ? null : ProjectParameterService.normalizeEnvironment(environmentCode);
        return new SecretCollection(parameters.findSecretEnvironmentCodes(projectId),
                parameters.findSecretsByProject(projectId, normalizedEnvironment));
    }

    @Transactional
    public void upsert(AuthenticatedPrincipal principal, String projectId, String environmentCode, String key, String value) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, SECRET_WRITERS);
        String normalizedEnvironment = ProjectParameterService.normalizeEnvironment(environmentCode);
        String normalizedKey = ProjectParameterService.normalizeKey(key);
        if (value == null || value.isEmpty() || value.length() > 20_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密钥值不能为空且不能超过 20000 字符");
        }
        parameters.upsertSecret(UlidCreator.getUlid().toString(), project.organizationId(), project.id(),
                normalizedEnvironment, normalizedKey, cipher.encrypt(value), principal.userId());
        audit.record(principal, project.organizationId(), project.id(), "ENVIRONMENT_SECRET_UPSERTED", "ENVIRONMENT_SECRET",
                normalizedEnvironment + ":" + normalizedKey,
                java.util.Map.of("environment", normalizedEnvironment, "key", normalizedKey));
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String projectId, String secretId) {
        ProjectSummary project = access.requireProjectParameterWrite(principal, projectId, SECRET_WRITERS);
        parameters.deleteSecret(secretId, projectId);
        audit.record(principal, project.organizationId(), project.id(), "ENVIRONMENT_SECRET_DELETED", "ENVIRONMENT_SECRET",
                secretId, java.util.Map.of());
    }

    public record SecretCollection(List<String> environments, List<EnvironmentSecret> secrets) {
    }
}
