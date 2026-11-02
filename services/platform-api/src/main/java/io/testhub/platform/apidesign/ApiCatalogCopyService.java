package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** One project transaction owns the source snapshot, copied assets, audit records and durable retry receipt. */
@Service
public class ApiCatalogCopyService {
    public static final int LIMIT = 200;
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private final ApiDesignRepository repository;
    private final ApiCatalogCopyRepository receipts;
    private final ApiDesignService design;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper json;
    public ApiCatalogCopyService(ApiDesignRepository repository, ApiCatalogCopyRepository receipts, ApiDesignService design,
                                 AccessService access, AuditService audit, ObjectMapper json) {
        this.repository = repository;
        this.receipts = receipts;
        this.design = design;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Preview preview(AuthenticatedPrincipal actor, String project, String sourceId) {
        access.requireProjectParameterWrite(actor,project,WRITERS);
        Snapshot snapshot=snapshot(project,sourceId);
        return new Preview(sourceId,snapshot.root.parentId(),snapshot.digest,snapshot.catalogs.size(),snapshot.definitions.size(),snapshot.itemCount,LIMIT);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result copy(AuthenticatedPrincipal actor, String project, String sourceId, Request request) {
        var scope=access.requireProjectParameterWrite(actor,project,WRITERS);
        if(request==null)throw bad("复制请求不能为空");
        String key=requestKey(request.requestKey());
        String expected=request.expectedSourceDigest()==null?"":request.expectedSourceDigest().trim().toLowerCase(Locale.ROOT);
        if(!expected.matches("[a-f0-9]{64}"))throw bad("请先读取目录复制预检，再提交复制");
        String payload=hash((sourceId+"\n"+expected).getBytes(StandardCharsets.UTF_8));
        repository.lockProject(project);
        var previous=receipts.find(scope.organizationId(),project,actor.userId(),key);
        if(previous.isPresent()) {
            if(!previous.get().payloadSha256().equals(payload))throw conflict("该复制请求Key已用于不同内容，请核对后发起新复制");
            try{return json.readValue(previous.get().resultJson(), Result.class);}
            catch(JsonProcessingException error){throw new IllegalStateException("目录复制回执损坏，未重建副本",error);}
        }
        Snapshot snapshot=snapshot(project,sourceId);
        if(snapshot.itemCount>LIMIT)throw bad("单次目录复制最多200个目录与接口，未创建副本");
        if(!snapshot.digest.equals(expected))throw conflict("源目录或当前版本已变化，请重新预检后确认复制");
        var mapping=new LinkedHashMap<String, String>();
        var newDefinitions=new ArrayList<String>();
        var routeRegistry=new ArrayList<>(repository.findDefinitions(project,null));
        for(ApiCatalog catalog:snapshot.catalogs) {
            boolean root=catalog.id().equals(sourceId);
            String parent=root?catalog.parentId():mapping.get(catalog.parentId());
            String name=root?copyName(project,catalog):catalog.name();
            ApiCatalog created=design.createCatalog(actor,project,new ApiDesignService.CatalogInput(name,parent,catalog.sortOrder()));
            mapping.put(catalog.id(),created.id());
        }
        for(Definition source:snapshot.definitions) {
            ApiDefinition api = source.api;
            ApiDefinitionVersion version = source.version;
            String name = api.name() + " 副本";
            if (name.length() > 200) name = name.substring(0, 200);
            ApiDefinition created=design.createDefinition(actor,project,new ApiDesignService.DefinitionInput(mapping.get(api.catalogId()),
                    api.method(),copyPath(api,routeRegistry),name,version.versionName(),version.summary(),version.description(),
                    parameters(version.requestHeaders()),parameters(version.requestQuery()),parameters(version.requestPath()),
                    version.requestBody(),version.responseBody(),metadata(version.exampleResponse())));
            routeRegistry.add(created);
            newDefinitions.add(created.id());
        }
        Result result=new Result(mapping.get(sourceId),mapping.size(),newDefinitions.size(), List.copyOf(mapping.values()), List.copyOf(newDefinitions));
        try {receipts.insert(UlidCreator.getUlid().toString(),scope.organizationId(),project,actor.userId(),key,payload,json.writeValueAsString(result));}
        catch(JsonProcessingException error){throw new IllegalStateException("目录复制回执编码失败",error);}
        audit.record(actor,scope.organizationId(),project, "API_CATALOG_COPIED", "API_CATALOG",result.rootCatalogId(),
                Map.of("catalogCount",result.catalogCount(), "definitionCount",result.definitionCount(), "sourceCatalogId",sourceId));
        return result;
    }

    private Snapshot snapshot(String project, String sourceId) {
        List<ApiCatalog> all=repository.findCatalogs(project);
        ApiCatalog root=all.stream().filter(c->c.id().equals(sourceId)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND, "源接口目录不存在"));
        if(root.parentId()!=null&&all.stream().noneMatch(c->c.id().equals(root.parentId())))throw conflict("源目录父级缺失，未复制");
        var tree = new ArrayList<ApiCatalog>();
        var queue = new ArrayDeque<ApiCatalog>();
        var visited = new HashSet<String>();
        queue.add(root);
        while(!queue.isEmpty()) {
            ApiCatalog next = queue.removeFirst();
            if (!visited.add(next.id())) throw conflict("目录结构存在循环，未复制");
            tree.add(next);
            all.stream().filter(c->next.id().equals(c.parentId())).sorted(Comparator.comparingInt(ApiCatalog::sortOrder).thenComparing(ApiCatalog::id)).forEach(queue::addLast);
        }
        List<ApiDefinition> apis=repository.findDefinitions(project,null).stream().filter(a->visited.contains(a.catalogId())).sorted(Comparator.comparing(ApiDefinition::id)).toList();
        int count = tree.size() + apis.size();
        var definitions = new ArrayList<Definition>();
        for(ApiDefinition api:apis) {
            ApiDefinitionVersion chosen=null;
            if(count<=LIMIT) {
                List<ApiDefinitionVersion> versions=repository.findVersions(project,api.id());
                chosen=versions.stream().filter(v->v.id().equals(api.currentVersionId())).findFirst()
                        .orElseGet(()->versions.stream().findFirst().orElseThrow(()->conflict("源接口没有可复制的当前版本，未复制")));
            }
            definitions.add(new Definition(api,chosen));
        }
        try {String digest=hash(json.writeValueAsBytes(List.of(tree,definitions, Objects.toString(root.parentId(), ""))));return new Snapshot(root, List.copyOf(tree), List.copyOf(definitions),count,digest);}
        catch(JsonProcessingException error){throw new IllegalStateException("目录复制快照编码失败",error);}
    }
    private String copyName(String project, ApiCatalog root) {
        int index=1;
        while(true) {
            String suffix = " 副本" + (index == 1 ? "" : " " + index);
            String prefix = root.name();
            if (prefix.length() + suffix.length() > 160) prefix = prefix.substring(0, 160 - suffix.length());
            String name=prefix+suffix;if(!repository.catalogNameExists(project,root.parentId(),name, ""))return name;index++;
        }
    }
    private String copyPath(ApiDefinition source, List<ApiDefinition> apis) {
        String path=source.path().replaceAll("/+$", "");String base=(path.isEmpty()?"/":path)+"-copy";String candidate=base;int index=2;
        var paths=new HashSet<String>();for(var api:apis)if(api.method().equalsIgnoreCase(source.method()))paths.add(api.path());
        while(paths.contains(candidate))candidate=base+"-"+index++;
        return candidate;
    }
    private List<ApiDesignService.ParameterInput> parameters(String value) {
        try{return json.readValue(value,new TypeReference<List<ApiDesignService.ParameterInput>>(){});}
        catch(JsonProcessingException error){throw conflict("源接口参数损坏，未复制");}
    }
    private Map<String, Object> metadata(String value) {
        try{return json.readValue(value,new TypeReference<Map<String, Object>>(){});}
        catch(JsonProcessingException error){throw conflict("源接口响应元数据损坏，未复制");}
    }
    private String requestKey(String value) {
        try {String normalized=UUID.fromString(value).toString();if(!normalized.equalsIgnoreCase(value))throw new IllegalArgumentException();return normalized;}
        catch(IllegalArgumentException|NullPointerException error){throw bad("复制请求Key必须为UUID");}
    }
    private String hash(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}}
    private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private record Definition(ApiDefinition api, ApiDefinitionVersion version) { }
    private record Snapshot(ApiCatalog root, List<ApiCatalog> catalogs, List<Definition> definitions, int itemCount, String digest) { }
    public record Request(String requestKey, String expectedSourceDigest) { }
    public record Preview(String sourceCatalogId, String targetParentId, String sourceDigest, int catalogCount, int definitionCount, int itemCount, int limit) { }
    public record Result(String rootCatalogId, int catalogCount, int definitionCount, List<String> catalogIds, List<String> definitionIds) { }
}
