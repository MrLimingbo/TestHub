package io.testhub.platform.apidesign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.access.ProjectSummary;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiInterchangeService {
    private static final Set<String> WRITERS = Set.of("SYSTEM_ADMIN", "ORG_ADMIN", "PROJECT_ADMIN", "TEST_LEAD", "TESTER");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");

    private final ApiDesignService design;
    private final ApiDesignRepository repository;
    private final AccessService access;
    private final AuditService audit;
    private final ObjectMapper json;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public ApiInterchangeService(ApiDesignService design, ApiDesignRepository repository, AccessService access,
                                 AuditService audit, ObjectMapper json) {
        this.design = design;
        this.repository = repository;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public ImportResult importOpenApi(AuthenticatedPrincipal principal, String projectId, OpenApiImportInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || blank(input.document())) throw rejected("OpenAPI 文档不能为空");
        requireCatalog(projectId, input.catalogId());

        JsonNode document = parseDocument(input.document());
        List<ApiDesignService.DefinitionInput> definitions = openApiDefinitions(document, input.catalogId());
        if (definitions.isEmpty()) throw rejected("OpenAPI 文档中没有可导入的 HTTP 接口");

        for (ApiDesignService.DefinitionInput definition : definitions) {
            design.createDefinition(principal, projectId, definition);
        }
        audit.record(principal, project.organizationId(), projectId, "API_OPENAPI_IMPORTED", "API_DEFINITION", projectId,
                Map.of("count", definitions.size(), "format", document.has("swagger") ? "SWAGGER_2" : "OPENAPI"));
        return new ImportResult("OPENAPI", definitions.size(), definitions.stream()
                .map(item -> item.method() + " " + item.path()).toList());
    }

    @Transactional
    public ImportResult importCurl(AuthenticatedPrincipal principal, String projectId, CurlImportInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || blank(input.command())) throw rejected("cURL 命令不能为空");
        requireCatalog(projectId, input.catalogId());

        ApiDesignService.DefinitionInput definition = curlDefinition(input.catalogId(), input.name(), input.command());
        design.createDefinition(principal, projectId, definition);
        audit.record(principal, project.organizationId(), projectId, "API_CURL_IMPORTED", "API_DEFINITION", projectId,
                Map.of("method", definition.method(), "path", definition.path()));
        return new ImportResult("CURL", 1, List.of(definition.method() + " " + definition.path()));
    }

    public ObjectNode exportOpenApi(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        if (!blank(catalogId)) requireCatalog(projectId, catalogId);

        ObjectNode root = json.createObjectNode();
        root.put("openapi", "3.1.0");
        ObjectNode info = root.putObject("info");
        info.put("title", "TestHub API Export");
        info.put("version", "1.0.0");
        ObjectNode paths = root.putObject("paths");

        for (ApiDefinition definition : repository.findDefinitions(projectId, blankToNull(catalogId))) {
            ApiDefinitionVersion version = currentVersion(projectId, definition);
            if (version == null) continue;

            ObjectNode operation = paths.withObjectProperty(definition.path())
                    .putObject(definition.method().toLowerCase(Locale.ROOT));
            operation.put("summary", version.summary());
            operation.put("description", version.description());
            ArrayNode parameters = operation.putArray("parameters");
            appendParameters(parameters, version.requestPath(), "path");
            appendParameters(parameters, version.requestQuery(), "query");
            appendParameters(parameters, version.requestHeaders(), "header");

            if (!version.requestBody().isBlank()) {
                ObjectNode requestBody = operation.putObject("requestBody");
                requestBody.put("required", true);
                requestBody.putObject("content").putObject("application/json")
                        .set("example", jsonOrText(version.requestBody()));
            }
            ObjectNode response = operation.putObject("responses").putObject("200");
            response.put("description", "Success");
            response.putObject("content").putObject("application/json")
                    .set("example", jsonOrText(version.responseBody()));
        }
        return root;
    }

    @Transactional
    public ImportResult importPostman(AuthenticatedPrincipal principal, String projectId, PostmanImportInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || blank(input.document())) throw rejected("Postman Collection 文档不能为空");
        requireCatalog(projectId, input.catalogId());

        JsonNode collection = parsePostmanCollection(input.document());
        List<ApiDesignService.DefinitionInput> definitions = postmanDefinitions(collection, input.catalogId());
        if (definitions.isEmpty()) throw rejected("Postman Collection 中没有可导入的 HTTP 接口");

        for (ApiDesignService.DefinitionInput definition : definitions) {
            design.createDefinition(principal, projectId, definition);
        }
        audit.record(principal, project.organizationId(), projectId, "API_POSTMAN_COLLECTION_IMPORTED", "API_DEFINITION", projectId,
                Map.of("count", definitions.size(), "format", "POSTMAN_COLLECTION_V2"));
        return new ImportResult("POSTMAN", definitions.size(), definitions.stream()
                .map(item -> item.method() + " " + item.path()).toList());
    }

    public ObjectNode exportPostman(AuthenticatedPrincipal principal, String projectId, String catalogId) {
        access.requireProjectRead(principal, projectId);
        if (!blank(catalogId)) requireCatalog(projectId, catalogId);

        ObjectNode root = json.createObjectNode();
        ObjectNode info = root.putObject("info");
        info.put("name", "TestHub API Export");
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        root.putArray("variable").addObject().put("key", "baseUrl").put("value", "https://api.example.com");
        ArrayNode items = root.putArray("item");
        for (ApiDefinition definition : repository.findDefinitions(projectId, blankToNull(catalogId))) {
            ApiDefinitionVersion version = currentVersion(projectId, definition);
            if (version == null) continue;
            appendPostmanItem(items, definition, version);
        }
        return root;
    }

    @Transactional
    public ImportResult importHar(AuthenticatedPrincipal principal, String projectId, HarImportInput input) {
        ProjectSummary project = writeAccess(principal, projectId);
        if (input == null || blank(input.document())) throw rejected("HAR 文档不能为空");
        requireCatalog(projectId, input.catalogId());

        List<ApiDesignService.DefinitionInput> definitions = harDefinitions(input.document(), input.catalogId());
        if (definitions.isEmpty()) throw rejected("HAR 文档中没有可导入的 HTTP 接口");

        for (ApiDesignService.DefinitionInput definition : definitions) {
            design.createDefinition(principal, projectId, definition);
        }
        audit.record(principal, project.organizationId(), projectId, "API_HAR_IMPORTED", "API_DEFINITION", projectId,
                Map.of("count", definitions.size(), "format", "HAR_1_2"));
        return new ImportResult("HAR", definitions.size(), definitions.stream()
                .map(item -> item.method() + " " + item.path()).toList());
    }

    public ObjectNode exportHar(AuthenticatedPrincipal principal, String projectId, String catalogId, String baseUrl) {
        access.requireProjectRead(principal, projectId);
        if (!blank(catalogId)) requireCatalog(projectId, catalogId);
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);

        ObjectNode root = json.createObjectNode();
        ObjectNode log = root.putObject("log");
        log.put("version", "1.2");
        log.putObject("creator").put("name", "TestHub").put("version", "0.1.0");
        ArrayNode entries = log.putArray("entries");
        for (ApiDefinition definition : repository.findDefinitions(projectId, blankToNull(catalogId))) {
            ApiDefinitionVersion version = currentVersion(projectId, definition);
            if (version != null) appendHarEntry(entries, definition, version, normalizedBaseUrl);
        }
        return root;
    }

    public String exportCurl(AuthenticatedPrincipal principal, String projectId, String apiId, String baseUrl) {
        access.requireProjectRead(principal, projectId);
        ApiDefinition definition = repository.findDefinition(projectId, apiId)
                .orElseThrow(() -> rejected("接口不存在"));
        ApiDefinitionVersion version = currentVersion(projectId, definition);
        if (version == null) throw rejected("接口尚未创建版本");

        String url = normalizeBaseUrl(baseUrl) + definition.path() + queryString(parameters(version.requestQuery()));
        StringBuilder command = new StringBuilder("curl -X ").append(definition.method()).append(" '").append(url).append("'");
        for (ApiDesignService.ParameterInput header : parameters(version.requestHeaders())) {
            command.append(" \\\n  -H '").append(header.name()).append(":");
            if (!header.example().isBlank()) command.append(" ").append(header.example());
            command.append("'");
        }
        if (!version.requestBody().isBlank()) {
            command.append(" \\\n  --data-raw '").append(version.requestBody().replace("'", "'\\\"'\\\"'")).append("'");
        }
        return command.toString();
    }

    private List<ApiDesignService.DefinitionInput> postmanDefinitions(JsonNode collection, String catalogId) {
        String version = normalizedVersion(collection.path("info").path("version").asText("1.0.0"));
        List<ApiDesignService.DefinitionInput> result = new ArrayList<>();
        appendPostmanDefinitions(collection.path("item"), catalogId, List.of(), version, result);
        return result;
    }

    private List<ApiDesignService.DefinitionInput> harDefinitions(String source, String catalogId) {
        JsonNode root;
        try {
            root = json.readTree(source);
        } catch (JsonProcessingException exception) {
            throw rejected("HAR 文档不是有效的 JSON");
        }
        JsonNode log = root.path("log");
        if (!log.isObject() || !log.path("version").asText("").equals("1.2")) {
            throw rejected("仅支持 HAR 1.2 文档");
        }
        JsonNode entries = log.path("entries");
        if (!entries.isArray()) throw rejected("HAR 文档缺少 log.entries 数组");

        List<ApiDesignService.DefinitionInput> result = new ArrayList<>();
        for (JsonNode entry : entries) {
            HarRequest request = harRequest(entry.path("request"));
            if (request == null) continue;
            HarResponse response = harResponse(entry.path("response"));
            String name = request.method() + " " + request.path();
            String description = firstNonBlank(entry.path("comment").asText(""), entry.path("request").path("comment").asText(""));
            result.add(new ApiDesignService.DefinitionInput(catalogId, request.method(), request.path(), name, "1.0.0",
                    "从 HAR 导入", description, request.headers(), request.query(), pathParameters(request.path()), request.body(),
                    response.body(), Map.of("statusCode", response.statusCode())));
        }
        return result;
    }

    private HarRequest harRequest(JsonNode node) {
        if (!node.isObject()) return null;
        String method = node.path("method").asText("").trim().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
            if (method.isBlank()) return null;
            throw rejected("HAR 文档包含不支持的 HTTP 方法：" + method);
        }
        String rawUrl = node.path("url").asText("").trim();
        if (rawUrl.isBlank()) throw rejected("HAR 请求缺少 URL");
        URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (IllegalArgumentException exception) {
            throw rejected("HAR 请求 URL 格式不合法");
        }
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) return null;
        String path = uri.getRawPath();
        if (blank(path)) path = "/";
        List<ApiDesignService.ParameterInput> query = harQuery(node.path("queryString"));
        if (query.isEmpty()) query = query(uri.getRawQuery());
        List<ApiDesignService.ParameterInput> headers = harHeaders(node.path("headers"));
        String body = harRequestBody(node.path("postData"), headers);
        return new HarRequest(method, path, query, headers, body);
    }

    private List<ApiDesignService.ParameterInput> harHeaders(JsonNode node) {
        if (!node.isArray()) return new ArrayList<>();
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        node.forEach(header -> {
            String name = header.path("name").asText("").trim();
            if (!name.isBlank()) result.add(new ApiDesignService.ParameterInput(name, "string", false,
                    header.path("value").asText(""), header.path("comment").asText("")));
        });
        return result;
    }

    private List<ApiDesignService.ParameterInput> harQuery(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        node.forEach(parameter -> {
            String name = parameter.path("name").asText("").trim();
            if (!name.isBlank()) result.add(new ApiDesignService.ParameterInput(name, "string", false,
                    parameter.path("value").asText(""), parameter.path("comment").asText("")));
        });
        return result;
    }

    private String harRequestBody(JsonNode node, List<ApiDesignService.ParameterInput> headers) {
        if (!node.isObject()) return "";
        String mimeType = node.path("mimeType").asText("");
        if (!mimeType.isBlank()) ensureHeader(headers, "Content-Type", mimeType);
        if (node.hasNonNull("text")) return node.path("text").asText("");
        JsonNode params = node.path("params");
        if (!params.isArray()) return "";
        if (mimeType.toLowerCase(Locale.ROOT).contains("x-www-form-urlencoded")) {
            List<String> values = new ArrayList<>();
            params.forEach(parameter -> {
                String name = parameter.path("name").asText("");
                if (!name.isBlank()) values.add(encode(name) + "=" + encode(parameter.path("value").asText("")));
            });
            return String.join("&", values);
        }
        ObjectNode values = json.createObjectNode();
        params.forEach(parameter -> {
            String name = parameter.path("name").asText("");
            if (!name.isBlank() && !parameter.hasNonNull("fileName")) values.put(name, parameter.path("value").asText(""));
        });
        return printable(values);
    }

    private HarResponse harResponse(JsonNode node) {
        if (!node.isObject()) return new HarResponse(200, "{}");
        int statusCode = node.path("status").asInt(200);
        if (statusCode < 100 || statusCode > 599) statusCode = 200;
        JsonNode content = node.path("content");
        if (!content.isObject() || !content.hasNonNull("text")) return new HarResponse(statusCode, "{}");
        String body = content.path("text").asText("");
        if (content.path("encoding").asText("").equalsIgnoreCase("base64")) {
            String mimeType = content.path("mimeType").asText("application/octet-stream");
            body = "data:" + mimeType + ";base64," + body;
        }
        return new HarResponse(statusCode, body);
    }

    private void appendPostmanDefinitions(JsonNode items, String catalogId, List<String> folders, String version,
                                          List<ApiDesignService.DefinitionInput> result) {
        if (!items.isArray()) return;
        for (JsonNode item : items) {
            String itemName = item.path("name").asText("").trim();
            JsonNode children = item.path("item");
            if (children.isArray()) {
                List<String> nestedFolders = new ArrayList<>(folders);
                if (!itemName.isBlank()) nestedFolders.add(itemName);
                appendPostmanDefinitions(children, catalogId, nestedFolders, version, result);
                continue;
            }

            JsonNode request = item.path("request");
            if (!request.isObject()) continue;
            String method = request.path("method").asText("GET").trim().toUpperCase(Locale.ROOT);
            if (!HTTP_METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                throw rejected("Postman Collection 包含不支持的 HTTP 方法：" + method);
            }
            PostmanUrl url = postmanUrl(request.path("url"));
            List<ApiDesignService.ParameterInput> headers = postmanHeaders(request.path("header"));
            String body = postmanRequestBody(request.path("body"), headers);
            PostmanResponse response = postmanResponse(item.path("response"));
            String name = itemName.isBlank() ? method + " " + url.path() : itemName;
            if (!folders.isEmpty()) name = String.join(" / ", folders) + " / " + name;
            String description = postmanDescription(request.path("description"));
            result.add(new ApiDesignService.DefinitionInput(catalogId, method, url.path(), limited(name, 200), version,
                    itemName, description, headers, url.query(), pathParameters(url.path()), body, response.body(),
                    Map.of("statusCode", response.statusCode())));
        }
    }

    private JsonNode parsePostmanCollection(String source) {
        try {
            JsonNode collection = json.readTree(source);
            String schema = collection.path("info").path("schema").asText("");
            if (!schema.contains("schema.getpostman.com/json/collection/v2.")) {
                throw rejected("仅支持 Postman Collection v2.0 或 v2.1 JSON 文档");
            }
            return collection;
        } catch (JsonProcessingException exception) {
            throw rejected("Postman Collection 不是有效的 JSON 文档");
        }
    }

    private PostmanUrl postmanUrl(JsonNode node) {
        if (node.isTextual()) return postmanRawUrl(node.asText(), List.of());
        if (!node.isObject()) throw rejected("Postman 请求缺少 URL");
        List<ApiDesignService.ParameterInput> query = postmanQuery(node.path("query"));
        String raw = node.path("raw").asText("");
        String path = "";
        JsonNode pathNode = node.path("path");
        if (pathNode.isArray()) {
            List<String> parts = new ArrayList<>();
            pathNode.forEach(part -> { if (!part.asText().isBlank()) parts.add(part.asText()); });
            path = "/" + String.join("/", parts);
        } else if (pathNode.isTextual() && !pathNode.asText().isBlank()) {
            path = pathNode.asText();
        }
        if (path.isBlank()) return postmanRawUrl(raw, query);
        return new PostmanUrl(normalizePostmanPath(path), query.isEmpty() ? rawQuery(raw) : query);
    }

    private PostmanUrl postmanRawUrl(String raw, List<ApiDesignService.ParameterInput> explicitQuery) {
        if (blank(raw)) throw rejected("Postman 请求缺少 URL");
        String candidate = raw.trim().replaceAll("\\{\\{[^}]+}}", "");
        int queryIndex = candidate.indexOf('?');
        String query = queryIndex >= 0 ? candidate.substring(queryIndex + 1) : "";
        String pathCandidate = queryIndex >= 0 ? candidate.substring(0, queryIndex) : candidate;
        try {
            URI uri = URI.create(pathCandidate);
            if (uri.getScheme() != null) pathCandidate = uri.getRawPath();
        } catch (IllegalArgumentException exception) {
            throw rejected("Postman 请求 URL 格式不合法");
        }
        if (blank(pathCandidate)) pathCandidate = "/";
        return new PostmanUrl(normalizePostmanPath(pathCandidate), explicitQuery.isEmpty() ? query(query) : explicitQuery);
    }

    private List<ApiDesignService.ParameterInput> postmanHeaders(JsonNode node) {
        if (!node.isArray()) return new ArrayList<>();
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        node.forEach(header -> {
            if (header.path("disabled").asBoolean(false) || header.path("key").asText().isBlank()) return;
            result.add(new ApiDesignService.ParameterInput(header.path("key").asText().trim(), "string", false,
                    header.path("value").asText(""), header.path("description").asText("")));
        });
        return result;
    }

    private List<ApiDesignService.ParameterInput> postmanQuery(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        node.forEach(query -> {
            if (query.path("disabled").asBoolean(false) || query.path("key").asText().isBlank()) return;
            result.add(new ApiDesignService.ParameterInput(query.path("key").asText().trim(), "string", false,
                    query.path("value").asText(""), query.path("description").asText("")));
        });
        return result;
    }

    private String postmanRequestBody(JsonNode node, List<ApiDesignService.ParameterInput> headers) {
        if (!node.isObject()) return "";
        String mode = node.path("mode").asText("");
        return switch (mode) {
            case "raw" -> {
                if (node.path("options").path("raw").path("language").asText().equals("json")) ensureHeader(headers, "Content-Type", "application/json");
                yield node.path("raw").asText("");
            }
            case "urlencoded" -> {
                ensureHeader(headers, "Content-Type", "application/x-www-form-urlencoded");
                yield postmanForm(node.path("urlencoded"), false);
            }
            case "formdata" -> {
                ensureHeader(headers, "Content-Type", "multipart/form-data");
                yield postmanForm(node.path("formdata"), true);
            }
            case "graphql" -> {
                ObjectNode graphql = json.createObjectNode();
                graphql.put("query", node.path("graphql").path("query").asText(""));
                String variables = node.path("graphql").path("variables").asText("");
                graphql.set("variables", jsonOrText(variables.isBlank() ? "{}" : variables));
                ensureHeader(headers, "Content-Type", "application/json");
                yield printable(graphql);
            }
            default -> "";
        };
    }

    private String postmanForm(JsonNode node, boolean object) {
        if (!node.isArray()) return object ? "{}" : "";
        if (!object) {
            List<String> values = new ArrayList<>();
            node.forEach(field -> {
                if (!field.path("disabled").asBoolean(false) && !field.path("key").asText().isBlank()) {
                    values.add(encode(field.path("key").asText()) + "=" + encode(field.path("value").asText("")));
                }
            });
            return String.join("&", values);
        }
        ObjectNode values = json.createObjectNode();
        node.forEach(field -> {
            if (!field.path("disabled").asBoolean(false) && !field.path("key").asText().isBlank() && !field.path("type").asText().equals("file")) {
                values.put(field.path("key").asText(), field.path("value").asText(""));
            }
        });
        return printable(values);
    }

    private PostmanResponse postmanResponse(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) return new PostmanResponse(200, "{}");
        JsonNode response = node.get(0);
        return new PostmanResponse(response.path("code").asInt(200), response.path("body").asText("{}"));
    }

    private String postmanDescription(JsonNode node) {
        if (node.isTextual()) return node.asText("");
        return node.path("content").asText("");
    }

    private List<ApiDesignService.ParameterInput> pathParameters(String path) {
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{([^}/]+)}").matcher(path);
        while (matcher.find()) result.add(new ApiDesignService.ParameterInput(matcher.group(1), "string", true, "", ""));
        return result;
    }

    private String normalizePostmanPath(String path) {
        String normalized = path.trim().replaceAll("(?<=/):([A-Za-z0-9_-]+)", "{$1}");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (normalized.contains(" ")) throw rejected("Postman 请求路径不能包含空格");
        return normalized;
    }

    private List<ApiDesignService.ParameterInput> rawQuery(String raw) {
        int index = raw == null ? -1 : raw.indexOf('?');
        return index < 0 ? List.of() : query(raw.substring(index + 1));
    }

    private void ensureHeader(List<ApiDesignService.ParameterInput> headers, String name, String value) {
        if (headers.stream().noneMatch(header -> header.name().equalsIgnoreCase(name))) {
            headers.add(new ApiDesignService.ParameterInput(name, "string", false, value, ""));
        }
    }

    private void appendPostmanItem(ArrayNode target, ApiDefinition definition, ApiDefinitionVersion version) {
        ObjectNode item = target.addObject();
        item.put("name", definition.name());
        ObjectNode request = item.putObject("request");
        request.put("method", definition.method());
        request.put("description", version.description());
        ArrayNode headers = request.putArray("header");
        for (ApiDesignService.ParameterInput header : parameters(version.requestHeaders())) {
            headers.addObject().put("key", header.name()).put("value", header.example()).put("type", "text");
        }
        String rawUrl = "{{baseUrl}}" + definition.path() + queryString(parameters(version.requestQuery()));
        ObjectNode url = request.putObject("url");
        url.put("raw", rawUrl);
        url.putArray("host").add("{{baseUrl}}");
        ArrayNode path = url.putArray("path");
        for (String part : definition.path().substring(1).split("/")) if (!part.isBlank()) path.add(part);
        ArrayNode query = url.putArray("query");
        for (ApiDesignService.ParameterInput parameter : parameters(version.requestQuery())) {
            query.addObject().put("key", parameter.name()).put("value", parameter.example()).put("description", parameter.description());
        }
        if (!version.requestBody().isBlank()) {
            ObjectNode body = request.putObject("body");
            body.put("mode", "raw");
            body.put("raw", version.requestBody());
            body.putObject("options").putObject("raw").put("language", version.requestBody().trim().startsWith("{") || version.requestBody().trim().startsWith("[") ? "json" : "text");
        }
        ObjectNode response = item.putArray("response").addObject();
        response.put("name", "Success");
        response.put("status", "OK");
        response.put("code", 200);
        response.put("body", version.responseBody());
    }

    private void appendHarEntry(ArrayNode entries, ApiDefinition definition, ApiDefinitionVersion version, String baseUrl) {
        ObjectNode entry = entries.addObject();
        entry.put("startedDateTime", Instant.now().toString());
        entry.put("time", 0);
        ObjectNode request = entry.putObject("request");
        request.put("method", definition.method());
        request.put("url", baseUrl + definition.path() + queryString(parameters(version.requestQuery())));
        request.put("httpVersion", "HTTP/1.1");
        appendHarHeaders(request.putArray("headers"), parameters(version.requestHeaders()));
        ArrayNode query = request.putArray("queryString");
        for (ApiDesignService.ParameterInput parameter : parameters(version.requestQuery())) {
            query.addObject().put("name", parameter.name()).put("value", parameter.example());
        }
        request.putArray("cookies");
        request.put("headersSize", -1);
        request.put("bodySize", version.requestBody().getBytes(StandardCharsets.UTF_8).length);
        if (!version.requestBody().isBlank()) {
            ObjectNode postData = request.putObject("postData");
            postData.put("mimeType", contentType(parameters(version.requestHeaders()), version.requestBody()));
            postData.put("text", version.requestBody());
        }

        int statusCode = responseStatus(version.exampleResponse());
        String responseBody = version.responseBody();
        ObjectNode response = entry.putObject("response");
        response.put("status", statusCode);
        response.put("statusText", harStatusText(statusCode));
        response.put("httpVersion", "HTTP/1.1");
        response.putArray("headers");
        response.putArray("cookies");
        ObjectNode content = response.putObject("content");
        content.put("size", responseBody.getBytes(StandardCharsets.UTF_8).length);
        content.put("mimeType", contentType(List.of(), responseBody));
        content.put("text", responseBody);
        response.put("redirectURL", "");
        response.put("headersSize", -1);
        response.put("bodySize", responseBody.getBytes(StandardCharsets.UTF_8).length);
        entry.putObject("cache");
        ObjectNode timings = entry.putObject("timings");
        timings.put("send", 0);
        timings.put("wait", 0);
        timings.put("receive", 0);
    }

    private void appendHarHeaders(ArrayNode target, List<ApiDesignService.ParameterInput> headers) {
        for (ApiDesignService.ParameterInput header : headers) {
            target.addObject().put("name", header.name()).put("value", header.example());
        }
    }

    private int responseStatus(String exampleResponse) {
        try {
            int statusCode = json.readTree(exampleResponse).path("statusCode").asInt(200);
            return statusCode >= 100 && statusCode <= 599 ? statusCode : 200;
        } catch (JsonProcessingException exception) {
            return 200;
        }
    }

    private String contentType(List<ApiDesignService.ParameterInput> headers, String body) {
        for (ApiDesignService.ParameterInput header : headers) {
            if (header.name().equalsIgnoreCase("Content-Type") && !header.example().isBlank()) return header.example();
        }
        String trimmed = body.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[") ? "application/json" : "text/plain";
    }

    private String harStatusText(int statusCode) {
        return switch (statusCode) {
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            default -> "OK";
        };
    }

    private List<ApiDesignService.DefinitionInput> openApiDefinitions(JsonNode document, String catalogId) {
        boolean swagger2 = document.has("swagger") && document.path("swagger").asText().startsWith("2.");
        if (!swagger2 && !document.has("openapi")) throw rejected("仅支持 OpenAPI 3.x 或 Swagger 2.0 文档");
        JsonNode paths = document.path("paths");
        if (!paths.isObject()) throw rejected("OpenAPI 文档缺少 paths 节点");

        String version = normalizedVersion(document.path("info").path("version").asText("1.0.0"));
        String basePath = swagger2 ? normalizeBasePath(document.path("basePath").asText("")) : "";
        List<ApiDesignService.DefinitionInput> result = new ArrayList<>();
        paths.fields().forEachRemaining(pathEntry -> {
            String path = basePath + pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();
            pathNode.fields().forEachRemaining(operationEntry -> {
                String method = operationEntry.getKey().toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) return;

                JsonNode operation = operationEntry.getValue();
                List<ApiDesignService.ParameterInput> headers = new ArrayList<>();
                List<ApiDesignService.ParameterInput> query = new ArrayList<>();
                List<ApiDesignService.ParameterInput> pathParameters = new ArrayList<>();
                appendOpenApiParameters(pathNode.path("parameters"), headers, query, pathParameters);
                appendOpenApiParameters(operation.path("parameters"), headers, query, pathParameters);

                String requestBody = swagger2 ? swaggerRequestBody(operation.path("parameters"))
                        : requestBody(operation.path("requestBody"));
                String responseBody = responseBody(operation.path("responses"));
                String summary = operation.path("summary").asText("");
                String name = summary.isBlank() ? method.toUpperCase(Locale.ROOT) + " " + path : summary;
                result.add(new ApiDesignService.DefinitionInput(catalogId, method.toUpperCase(Locale.ROOT), path, name, version,
                        summary, operation.path("description").asText(""), headers, query, pathParameters,
                        requestBody, responseBody, Map.of("statusCode", 200)));
            });
        });
        return result;
    }

    private ApiDesignService.DefinitionInput curlDefinition(String catalogId, String name, String command) {
        List<String> tokens = shellTokens(command);
        if (tokens.isEmpty() || !tokens.getFirst().equals("curl")) throw rejected("cURL 命令必须以 curl 开头");

        String method = "GET";
        String url = null;
        String data = "";
        List<ApiDesignService.ParameterInput> headers = new ArrayList<>();
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ((token.equals("-X") || token.equals("--request")) && index + 1 < tokens.size()) {
                method = tokens.get(++index).toUpperCase(Locale.ROOT);
            } else if ((token.equals("-H") || token.equals("--header")) && index + 1 < tokens.size()) {
                headers.add(header(tokens.get(++index)));
            } else if ((token.equals("-d") || token.equals("--data") || token.equals("--data-raw") || token.equals("--data-binary")) && index + 1 < tokens.size()) {
                data = tokens.get(++index);
                if (method.equals("GET")) method = "POST";
            } else if (!token.startsWith("-") && url == null) {
                url = token;
            }
        }
        if (url == null) throw rejected("cURL 命令缺少请求 URL");

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw rejected("cURL URL 格式不合法");
        }
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw rejected("cURL 仅支持 HTTP 或 HTTPS URL");
        }
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) path = "/";
        List<ApiDesignService.ParameterInput> query = query(uri.getRawQuery());
        String displayName = blank(name) ? method + " " + path : name.trim();
        return new ApiDesignService.DefinitionInput(catalogId, method, path, displayName, "1.0.0", "从 cURL 导入", "", headers,
                query, List.of(), data, "{}", Map.of("statusCode", 200));
    }

    private JsonNode parseDocument(String source) {
        try {
            return json.readTree(source);
        } catch (JsonProcessingException ignored) {
            try {
                return yaml.readTree(source);
            } catch (JsonProcessingException exception) {
                throw rejected("OpenAPI 文档不是有效的 JSON 或 YAML");
            }
        }
    }

    private void appendOpenApiParameters(JsonNode node, List<ApiDesignService.ParameterInput> headers,
                                         List<ApiDesignService.ParameterInput> query, List<ApiDesignService.ParameterInput> path) {
        if (!node.isArray()) return;
        node.forEach(parameter -> {
            String location = parameter.path("in").asText();
            String name = parameter.path("name").asText();
            if (name.isBlank() || !Set.of("header", "query", "path").contains(location)) return;
            JsonNode schema = parameter.path("schema");
            String type = schema.path("type").asText(parameter.path("type").asText("string"));
            String example = parameter.path("example").asText(schema.path("example").asText(""));
            ApiDesignService.ParameterInput value = new ApiDesignService.ParameterInput(name, type,
                    parameter.path("required").asBoolean(location.equals("path")), example,
                    parameter.path("description").asText(""));
            switch (location) {
                case "header" -> headers.add(value);
                case "query" -> query.add(value);
                default -> path.add(value);
            }
        });
    }

    private String requestBody(JsonNode requestBody) {
        if (!requestBody.isObject()) return "";
        JsonNode media = requestBody.path("content").path("application/json");
        return printable(media.has("example") ? media.path("example") : media.path("schema"));
    }

    private String swaggerRequestBody(JsonNode parameters) {
        if (!parameters.isArray()) return "";
        for (JsonNode parameter : parameters) {
            if (parameter.path("in").asText().equals("body")) return printable(parameter.path("schema"));
        }
        return "";
    }

    private String responseBody(JsonNode responses) {
        if (!responses.isObject()) return "{}";
        JsonNode response = responses.has("200") ? responses.path("200")
                : responses.has("201") ? responses.path("201") : responses.path("default");
        JsonNode media = response.path("content").path("application/json");
        return printable(media.has("example") ? media.path("example")
                : media.isMissingNode() ? response.path("schema") : media.path("schema"));
    }

    private void appendParameters(ArrayNode target, String source, String location) {
        for (ApiDesignService.ParameterInput parameter : parameters(source)) {
            ObjectNode item = target.addObject();
            item.put("name", parameter.name());
            item.put("in", location);
            item.put("required", location.equals("path") || parameter.required());
            item.putObject("schema").put("type", parameter.type());
            if (!parameter.example().isBlank()) item.put("example", parameter.example());
            if (!parameter.description().isBlank()) item.put("description", parameter.description());
        }
    }

    private List<ApiDesignService.ParameterInput> parameters(String value) {
        try {
            JsonNode node = json.readTree(value);
            if (!node.isArray()) return List.of();
            List<ApiDesignService.ParameterInput> result = new ArrayList<>();
            node.forEach(item -> result.add(new ApiDesignService.ParameterInput(item.path("name").asText(),
                    item.path("type").asText("string"), item.path("required").asBoolean(false),
                    item.path("example").asText(""), item.path("description").asText(""))));
            return result;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private List<ApiDesignService.ParameterInput> query(String value) {
        if (blank(value)) return List.of();
        List<ApiDesignService.ParameterInput> result = new ArrayList<>();
        for (String item : value.split("&")) {
            String[] parts = item.split("=", 2);
            result.add(new ApiDesignService.ParameterInput(decode(parts[0]), "string", false,
                    parts.length == 2 ? decode(parts[1]) : "", ""));
        }
        return result;
    }

    private ApiDesignService.ParameterInput header(String value) {
        int split = value.indexOf(':');
        if (split <= 0) throw rejected("cURL 请求头格式不合法");
        return new ApiDesignService.ParameterInput(value.substring(0, split).trim(), "string", false,
                value.substring(split + 1).trim(), "");
    }

    private String queryString(List<ApiDesignService.ParameterInput> parameters) {
        if (parameters.isEmpty()) return "";
        return "?" + parameters.stream()
                .map(parameter -> encode(parameter.name()) + (parameter.example().isBlank() ? "" : "=" + encode(parameter.example())))
                .collect(Collectors.joining("&"));
    }

    private List<String> shellTokens(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char character : command.trim().toCharArray()) {
            if (escaped) {
                current.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\' && quote != '\'') {
                escaped = true;
                continue;
            }
            if (character == '\'' || character == '"') {
                if (quote == 0) quote = character;
                else if (quote == character) quote = 0;
                else current.append(character);
                continue;
            }
            if (Character.isWhitespace(character) && quote == 0) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quote != 0 || escaped) throw rejected("cURL 引号或转义字符不完整");
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private ApiDefinitionVersion currentVersion(String projectId, ApiDefinition definition) {
        return repository.findVersions(projectId, definition.id()).stream()
                .filter(version -> version.id().equals(definition.currentVersionId())).findFirst().orElse(null);
    }

    private void requireCatalog(String projectId, String catalogId) {
        if (blank(catalogId) || repository.findCatalog(projectId, catalogId).isEmpty()) throw rejected("目标接口目录不存在");
    }

    private ProjectSummary writeAccess(AuthenticatedPrincipal principal, String projectId) {
        return access.requireProjectParameterWrite(principal, projectId, WRITERS);
    }

    private String normalizeBaseUrl(String value) {
        String result = blank(value) ? "https://api.example.com" : value.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(result);
            if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw rejected("cURL 基础地址必须为 HTTP 或 HTTPS");
        }
        return result;
    }

    private String normalizedVersion(String value) {
        return value != null && value.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9._-]+)?") ? value : "1.0.0";
    }

    private String normalizeBasePath(String value) {
        if (blank(value) || value.equals("/")) return "";
        String normalized = value.startsWith("/") ? value : "/" + value;
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private JsonNode jsonOrText(String value) {
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            return json.getNodeFactory().textNode(value);
        }
    }

    private String printable(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.isEmpty()) return "{}";
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String limited(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String firstNonBlank(String first, String second) {
        return !blank(first) ? first : (second == null ? "" : second);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToNull(String value) {
        return blank(value) ? null : value.trim();
    }

    private ResponseStatusException rejected(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record OpenApiImportInput(String catalogId, String document) { }
    public record CurlImportInput(String catalogId, String name, String command) { }
    public record PostmanImportInput(String catalogId, String document) { }
    public record HarImportInput(String catalogId, String document) { }
    public record ImportResult(String format, int importedCount, List<String> items) { }
    private record PostmanUrl(String path, List<ApiDesignService.ParameterInput> query) { }
    private record PostmanResponse(int statusCode, String body) { }
    private record HarRequest(String method, String path, List<ApiDesignService.ParameterInput> query,
                              List<ApiDesignService.ParameterInput> headers, String body) { }
    private record HarResponse(int statusCode, String body) { }
}
