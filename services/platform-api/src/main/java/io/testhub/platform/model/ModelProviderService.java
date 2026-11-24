package io.testhub.platform.model;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.model.ModelProviderRepository.StoredProvider;
import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.security.SecretCipher;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static io.testhub.platform.model.ModelProviderController.*;

@Service
public class ModelProviderService {
    private final AccessService access;
    private final AuditService audit;
    private final SecretCipher cipher;
    private final ModelProviderRepository providers;
    private final ModelGateway gateway;

    ModelProviderService(AccessService access, AuditService audit, SecretCipher cipher,
                         ModelProviderRepository providers, ModelGateway gateway) {
        this.access = access;
        this.audit = audit;
        this.cipher = cipher;
        this.providers = providers;
        this.gateway = gateway;
    }

    public List<Provider> list(AuthenticatedPrincipal principal) {
        access.requireSystemAdmin(principal);
        return providers.list();
    }

    @Transactional
    public Provider save(AuthenticatedPrincipal principal, String id, ProviderInput input) {
        access.requireSystemAdmin(principal);
        StoredProvider existing = id == null ? null : requireVersion(id, input.version());
        String url = gateway.normalizeBaseUrl(input.apiBaseUrl());
        String key = suppliedKey(input.apiKey(), input.clearApiKey(), existing, url);
        boolean replaceKey = key != null || input.clearApiKey();
        List<Model> models = normalizeModels(input.models());
        Provider provider = new Provider(id == null ? UlidCreator.getUlid().toString() : id,
                input.displayName().strip(), url, input.protocol(), input.retryPolicy(),
                key != null || (!replaceKey && existing != null && existing.provider().apiKeyConfigured()),
                models, existing == null ? 1 : existing.provider().version() + 1);
        String ciphertext = key == null ? null : cipher.encrypt(key);

        if (existing == null) providers.insert(provider, ciphertext);
        else if (!providers.update(provider, ciphertext, replaceKey)) throw versionConflict();

        record(principal, provider.id(), existing == null ? "MODEL_PROVIDER_CREATED" : "MODEL_PROVIDER_UPDATED");
        return provider;
    }

    @Transactional
    public void delete(AuthenticatedPrincipal principal, String id) {
        access.requireSystemAdmin(principal);
        if (!providers.delete(id)) throw notFound();
        record(principal, id, "MODEL_PROVIDER_DELETED");
    }

    public List<Model> discover(AuthenticatedPrincipal principal, DiscoveryInput input) {
        access.requireSystemAdmin(principal);
        StoredProvider existing = input.providerId() == null ? null : requireVersion(input.providerId(), input.version());
        String url = gateway.normalizeBaseUrl(input.apiBaseUrl());
        String key = suppliedKey(input.apiKey(), input.clearApiKey(), existing, url);
        if (key == null && !input.clearApiKey() && existing != null) key = decrypt(existing);
        return gateway.discover(url, key, input.retryPolicy());
    }

    public TestResult test(AuthenticatedPrincipal principal, String id, TestInput input) {
        access.requireSystemAdmin(principal);
        StoredProvider stored = requireProvider(id);
        Model model = stored.provider().models().stream().filter(entry -> entry.id().equals(input.modelId())).findFirst()
                .orElseThrow(() -> rejected("请从已保存的模型目录中选择测试模型"));
        String effort = input.reasoningLevel() == null ? null : model.reasoningOptions().stream()
                .filter(option -> option.level() == input.reasoningLevel()).findFirst().map(ReasoningOption::apiValue)
                .orElseThrow(() -> rejected("当前模型没有配置该推理等级"));
        long started = System.nanoTime();
        String reply = gateway.test(stored.provider(), decrypt(stored), model, effort);
        return new TestResult(model.id(), reply, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private List<Model> normalizeModels(List<Model> models) {
        Set<String> ids = new HashSet<>();
        return models.stream().map(model -> {
            String id = model.id().strip();
            if (!ModelGateway.validModelId(id)) throw rejected("模型 ID 不能包含空格或控制字符");
            if (!ids.add(id)) throw rejected("模型目录中存在重复的模型 ID");
            String name = model.displayName() == null ? "" : model.displayName().strip();
            Set<ReasoningLevel> levels = EnumSet.noneOf(ReasoningLevel.class);
            List<ReasoningOption> options = model.reasoningOptions().stream().map(option -> {
                if (!levels.add(option.level())) throw rejected("同一模型不能重复配置推理等级");
                String apiValue = option.apiValue().strip();
                if (!apiValue.matches("[!-~]+")) throw rejected("推理等级的 API 参数值不能含有空格或控制字符");
                return new ReasoningOption(option.level(), apiValue);
            }).toList();
            return new Model(id, name, model.contextWindow(), model.maxOutputTokens(), model.acceptsImages(), options);
        }).toList();
    }

    private String suppliedKey(String value, boolean clear, StoredProvider existing, String url) {
        String key = value == null || value.isBlank() ? null : value.strip();
        if (key != null && (clear || !key.matches("[!-~]+"))) {
            throw rejected("API 密钥格式不合法，或同时指定了替换和清除密钥");
        }
        if (key == null && !clear && existing != null && existing.provider().apiKeyConfigured()
                && !existing.provider().apiBaseUrl().equals(url)) {
            throw rejected("API 地址已更改，请输入该地址的 API 密钥或清除已保存的密钥");
        }
        return key;
    }

    private String decrypt(StoredProvider stored) {
        return stored.ciphertext() == null ? null : cipher.decrypt(stored.ciphertext());
    }

    private StoredProvider requireProvider(String id) {
        return providers.find(id).orElseThrow(ModelProviderService::notFound);
    }

    private StoredProvider requireVersion(String id, Long version) {
        StoredProvider provider = requireProvider(id);
        if (version == null || provider.provider().version() != version) throw versionConflict();
        return provider;
    }

    private void record(AuthenticatedPrincipal principal, String id, String action) {
        audit.record(principal, null, null, action, "MODEL_PROVIDER", id, Map.of());
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "模型提供商不存在");
    }

    private static ResponseStatusException versionConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "模型配置已更新，请刷新页面后重新编辑");
    }

    private static ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
