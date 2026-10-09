package io.testhub.platform.functional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.security.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Governance definitions participate in the caller's asset-transfer transaction. */
@Service
public class FunctionalTransferGovernance {
    private final FunctionalGovernanceService governance;
    private final ObjectMapper json;

    public FunctionalTransferGovernance(FunctionalGovernanceService governance, ObjectMapper json) {
        this.governance = governance;
        this.json = json;
    }

    public Definitions export(AuthenticatedPrincipal principal, String project, Map<String, List<String>> assets) {
        var tags = governance.listTags(principal, project).stream().map(item -> new Tag(item.id(), item.name(), item.color())).toList();
        var fields = governance.listFields(principal, project, null).stream().filter(item -> assets.containsKey(item.assetType())).map(item -> {
            try {
                List<String> options = json.readValue(item.optionsJson(), new TypeReference<List<String>>() {});
                return new Field(item.id(), item.assetType(), item.name(), item.fieldKey(), item.fieldType(), item.required(), options);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw invalid("本地自定义字段选项无法读取，请先修复字段配置。");
            }
        }).toList();
        List<Binding> bindings = new ArrayList<>();
        assets.forEach((type, ids) -> ids.forEach(id -> {
            var tagIds = governance.listAssetTags(principal, project, type, id).stream().map(FunctionalTag::id).toList();
            if (!tagIds.isEmpty()) bindings.add(new Binding(type, id, tagIds));
        }));
        return new Definitions(tags, fields, bindings);
    }

    public void validate(Definitions definitions, Map<String, List<String>> assets, Set<String> ids) {
        if (definitions == null || definitions.tags() == null || definitions.fields() == null || definitions.bindings() == null)
            throw invalid("标签与字段迁移资料不完整。");
        if (definitions.tags().size() > 5000 || definitions.fields().size() > 5000 || definitions.bindings().size() > 5000)
            throw invalid("标签、字段或关联资产数量超过 5000。");
        Set<String> tagIds = new HashSet<>(), names = new HashSet<>(), fieldKeys = new HashSet<>(), boundAssets = new HashSet<>();
        for (var tag : definitions.tags()) {
            if (tag == null || tag.id() == null || !ids.add(tag.id()) || !names.add(normalize(tag.name())))
                throw invalid("标签标识或名称重复。");
            tagIds.add(tag.id());
        }
        for (var field : definitions.fields()) {
            if (field == null || field.id() == null || !ids.add(field.id()) || !assets.containsKey(field.assetType())
                    || !fieldKeys.add(field.assetType() + ":" + normalize(field.key()))) throw invalid("字段标识重复或资产类型不在迁移范围内。");
            if (field.options() == null || field.options().size() > 1000 || field.options().stream().anyMatch(Objects::isNull))
                throw invalid("字段选项无效或超过 1000 个。");
        }
        long associations = 0;
        for (var binding : definitions.bindings()) {
            if (binding == null || !assets.containsKey(binding.assetType()) || !assets.get(binding.assetType()).contains(binding.assetId())
                    || !boundAssets.add(binding.assetType() + ":" + binding.assetId())) throw invalid("标签关联资产重复或不在本次迁移范围内。");
            if (binding.tagIds() == null || binding.tagIds().size() > 5000
                    || binding.tagIds().size() != new HashSet<>(binding.tagIds()).size()
                    || binding.tagIds().stream().anyMatch(id -> id == null || !tagIds.contains(id))) throw invalid("标签关联重复或不在本次迁移范围内。");
            associations += binding.tagIds().size();
            if (associations > 50000) throw invalid("单次最多迁移 50000 条资产标签关联。");
        }
    }

    public void checkConflicts(AuthenticatedPrincipal principal, String project, Definitions definitions) {
        Set<String> names = new HashSet<>(), keys = new HashSet<>();
        governance.listTags(principal, project).forEach(item -> names.add(normalize(item.name())));
        governance.listFields(principal, project, null).forEach(item -> keys.add(item.assetType() + ":" + normalize(item.fieldKey())));
        for (var tag : definitions.tags()) if (names.contains(normalize(tag.name()))) throw conflict("目标项目已有标签：" + tag.name());
        for (var field : definitions.fields()) if (keys.contains(field.assetType() + ":" + normalize(field.key())))
            throw conflict("目标项目已有同类型字段标识：" + field.key());
    }

    public Counts transfer(AuthenticatedPrincipal principal, String project, Definitions definitions, Map<String, String> assetIds) {
        Map<String, String> tagIds = new HashMap<>();
        for (var tag : definitions.tags()) tagIds.put(tag.id(), governance.createTag(principal, project,
                new FunctionalGovernanceService.TagInput(tag.name(), tag.color())).id());
        for (var field : definitions.fields()) governance.createField(principal, project,
                new FunctionalGovernanceService.CustomFieldInput(field.assetType(), field.name(), field.key(), field.type(), field.required(), field.options()));
        for (var binding : definitions.bindings()) governance.replaceAssetTags(principal, project, binding.assetType(), assetIds.get(binding.assetId()),
                binding.tagIds().stream().map(tagIds::get).toList());
        return new Counts(definitions.tags().size(), definitions.fields().size(), definitions.bindings().stream().mapToInt(item -> item.tagIds().size()).sum());
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) throw invalid("标签名称或字段标识不能为空。");
        return value.trim().toLowerCase(Locale.ROOT);
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public record Definitions(@NotNull @Size(max=5000) List<@NotNull @Valid Tag> tags,
                              @NotNull @Size(max=5000) List<@NotNull @Valid Field> fields,
                              @NotNull @Size(max=5000) List<@NotNull @Valid Binding> bindings) {}
    public record Tag(@NotBlank @Size(max=26) String id, @NotBlank @Size(max=64) String name, @NotBlank @Size(max=24) String color) {}
    public record Field(@NotBlank @Size(max=26) String id, @NotBlank @Size(max=24) String assetType,
                        @NotBlank @Size(max=64) String name, @NotBlank @Size(max=64) String key,
                        @NotBlank @Size(max=24) String type, boolean required,
                        @NotNull @Size(max=1000) List<@NotBlank @Size(max=80) String> options) {}
    public record Binding(@NotBlank @Size(max=24) String assetType, @NotBlank @Size(max=26) String assetId,
                          @NotNull @Size(max=5000) List<@NotBlank @Size(max=26) String> tagIds) {}
    public record Counts(int tags, int fields, int bindings) {}
}
