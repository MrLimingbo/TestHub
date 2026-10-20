package io.testhub.platform.apidebug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Produces a conservative, manually reviewed template. Never modifies the native encrypted replay draft. */
@Component
public class ApiReplayTemplateSanitizer {
    private static final Pattern SENSITIVE=Pattern.compile("(?i)authorization|cookie|api[-_]?key|token|secret|password|passwd|session|jwt|credential");
    private static final Pattern VARIABLE=Pattern.compile("^(?:(?:Bearer|Basic) )?\\{\\{[A-Za-z_$][A-Za-z0-9_.$-]*}}$",Pattern.CASE_INSENSITIVE);
    private static final Set<String> HTTP=Set.of("name","method","url","headers","body","timeoutMs","environmentCode","auth","protocol");
    private static final Set<String> GRPC=Set.of("endpoint","tls","service","method","requestJson","timeoutMs","environmentCode","metadata","auth","descriptorId");
    private static final Set<String> WS=Set.of("url","headers","environmentCode","auth","subprotocols","subscriptions","reconnect");
    private final ObjectMapper json;
    public ApiReplayTemplateSanitizer(ObjectMapper json){this.json=json;}
    public record Template(JsonNode template,List<String> notices){}
    public Template sanitize(JsonNode raw,Set<String> known){
        if(raw==null||!raw.isObject()||raw.size()!=2||!raw.path("protocol").isTextual()||!raw.path("request").isObject())throw rejected();
        bounded(raw);
        String protocol=raw.path("protocol").asText();if(!Set.of("HTTP","GRAPHQL","SOAP","WEBSOCKET","GRPC").contains(protocol))throw rejected();
        var request=(ObjectNode)raw.path("request").deepCopy();var allowed=protocol.equals("GRPC")?GRPC:protocol.equals("WEBSOCKET")?WS:HTTP;
        request.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw rejected();});
        schema(request);
        Set<String> secrets=new HashSet<>(known);collectSecrets(request,secrets,0,new int[]{0});var notices=new TreeSet<String>();notices.add("MANUAL_REVIEW");
        // Preserve the schema keys, but scrub every string value, including credentials repeated in otherwise innocuous fields.
        request=(ObjectNode)walk(request,"",secrets,notices);
        if(request.hasNonNull("environmentCode")){request.putNull("environmentCode");notices.add("ENVIRONMENT");}
        if(request.hasNonNull("descriptorId")){request.putNull("descriptorId");notices.add("GRPC_DESCRIPTOR");}
        if(request.has("url"))request.put("url",url(request.path("url").asText(),secrets,notices));
        if(request.has("endpoint"))request.put("endpoint",url(request.path("endpoint").asText(),secrets,notices));
        for(String field:List.of("body","requestJson"))if(request.hasNonNull(field))request.put(field,body(request.path(field).asText(),secrets,notices));
        if(request.path("protocol").isObject()){
            var p=(ObjectNode)request.path("protocol");
            if(p.hasNonNull("variables"))p.put("variables",body(p.path("variables").asText(),secrets,notices));
            for(String field:List.of("query","envelope"))if(p.hasNonNull(field)&&!p.path(field).asText().isBlank()&&!VARIABLE.matcher(p.path(field).asText()).matches()){p.put(field,placeholder(field));notices.add("BODY");}
        }
        for(String field:List.of("headers","metadata"))if(request.has(field)){
            if(!request.path(field).isArray()||request.path(field).size()>48)throw rejected();
            for(var row:request.path(field)){
                if(!row.isObject())throw rejected();String key=row.path(field.equals("metadata")?"key":"name").asText();
                if(SENSITIVE.matcher(key).find()){((ObjectNode)row).put("value",secretValue(key,row.path("value").asText(),notices));}
            }
        }
        if(request.path("auth").isObject()){
            var auth=(ObjectNode)request.path("auth");
            for(String field:List.of("token","username","password","value"))if(auth.hasNonNull(field))auth.put(field,secretValue("AUTH_"+field,auth.path(field).asText(),notices));
        }
        if(request.has("subscriptions")){
            if(!request.path("subscriptions").isArray())throw rejected();var safe=json.createArrayNode();for(var v:request.path("subscriptions"))safe.add(body(v.asText(),secrets,notices));request.set("subscriptions",safe);
        }
        if(request.path("reconnect").isObject())((ObjectNode)request.path("reconnect")).put("enabled",false);
        var result=json.createObjectNode().put("protocol",protocol);result.set("request",request);return new Template(result,List.copyOf(notices));
    }
    private void schema(ObjectNode request){
        for(String field:List.of("auth","protocol","reconnect"))if(request.hasNonNull(field)){
            JsonNode value=request.path(field);if(!value.isObject())throw rejected();
            Set<String> allowed=field.equals("auth")?Set.of("type","token","username","password","key","value","placement"):field.equals("protocol")?Set.of("type","query","variables","operationName","envelope","soapAction"):Set.of("enabled","maxAttempts","delayMs");
            value.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw rejected();});
            if(!field.equals("reconnect"))value.forEach(v->{if(!v.isNull()&&!v.isTextual())throw rejected();});
        }
        for(String field:List.of("headers","metadata"))if(request.hasNonNull(field)){
            if(!request.path(field).isArray())throw rejected();String name=field.equals("headers")?"name":"key";
            for(JsonNode value:request.path(field))if(!value.isObject()||value.size()!=2||!value.path(name).isTextual()||!value.path("value").isTextual())throw rejected();
        }
    }
    private JsonNode walk(JsonNode v,String key,Set<String> secrets,Set<String> notices){
        if(SENSITIVE.matcher(key).find()&&!Set.of("key","descriptorId").contains(key)&&!key.equals("type")&& !v.isNull()){
            if(v.isTextual())return TextNode.valueOf(secretValue(key,v.asText(),notices));notices.add("AUTH");return TextNode.valueOf(placeholder(key));
        }
        if(v.isArray()){var out=json.createArrayNode();for(var x:v)out.add(walk(x,"",secrets,notices));return out;}
        if(v.isObject()){var out=json.createObjectNode();v.fields().forEachRemaining(e->out.set(e.getKey(),walk(e.getValue(),e.getKey(),secrets,notices)));return out;}
        return v.isTextual()?TextNode.valueOf(text(v.asText(),secrets,notices)):v.deepCopy();
    }
    private String text(String value,Set<String> secrets,Set<String> notices){
        if(value.matches("(?is).*(?:/|%2f)api(?:/|%2f)v1(?:/|%2f)projects(?:/|%2f).*")){notices.add("PROJECT_FILE");return placeholder("PROJECT_FILE");}
        if(VARIABLE.matcher(value).matches())return value;
        String result=value;for(String secret:secrets.stream().filter(Objects::nonNull).sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder())).toList())if(!secret.isBlank()){
            if(result.contains(secret)){result=result.replace(secret,placeholder("SECRET"));notices.add("AUTH");}
            String encoded=java.net.URLEncoder.encode(secret,java.nio.charset.StandardCharsets.UTF_8);if(result.contains(encoded)){result=result.replace(encoded,placeholder("SECRET"));notices.add("AUTH");}
        }return result;
    }
    private String secretValue(String key,String value,Set<String> notices){if(value.isBlank()||VARIABLE.matcher(value).matches())return value;notices.add("AUTH");String scheme=value.matches("(?i)^Bearer .*")?"Bearer ":value.matches("(?i)^Basic .*")?"Basic ":"";return scheme+placeholder(key);}
    private String url(String value,Set<String> secrets,Set<String> notices){
        value=text(value,secrets,notices).replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/?#]*@","$1");int fragment=value.indexOf('#');if(fragment>=0){value=value.substring(0,fragment);notices.add("URL");}int query=value.indexOf('?');if(query<0)return value;
        var parts=new ArrayList<String>();for(String part:value.substring(query+1).split("&")){String[] pair=part.split("=",2);String name=pair[0];try{name=java.net.URLDecoder.decode(name,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw rejected();}
            if(SENSITIVE.matcher(name).find()&&pair.length==2){String decoded;try{decoded=java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw rejected();}parts.add(pair[0]+"="+java.net.URLEncoder.encode(secretValue(name,decoded,notices),java.nio.charset.StandardCharsets.UTF_8));}else parts.add(part);
        }return value.substring(0,query+1)+String.join("&",parts);
    }
    private String body(String value,Set<String> secrets,Set<String> notices){if(value.isBlank()||VARIABLE.matcher(value).matches())return value;try{var parsed=json.readTree(value);if(parsed==null)return "";bounded(parsed);return json.writeValueAsString(walk(parsed,"",secrets,notices));}catch(java.io.IOException e){notices.add("BODY");return placeholder("BODY");}}
    private void collectSecrets(JsonNode value,Set<String> secrets,int depth,int[] count){if(depth>32||++count[0]>20000)throw rejected();if(value.isObject())value.fields().forEachRemaining(e->{if(SENSITIVE.matcher(e.getKey()).find())secretLeaves(e.getValue(),secrets);collectSecrets(e.getValue(),secrets,depth+1,count);});else if(value.isArray())for(var v:value)collectSecrets(v,secrets,depth+1,count);
        if(value.isObject())for(String field:List.of("body","requestJson","variables"))if(value.path(field).isTextual())try{var parsed=json.readTree(value.path(field).asText());if(parsed!=null){bounded(parsed);collectSecrets(parsed,secrets,depth+1,count);}}catch(java.io.IOException ignored){/* Opaque bodies become review placeholders. */}
        if(value.isObject())for(String field:List.of("url","endpoint"))if(value.path(field).isTextual()){
            String url=value.path(field).asText();int q=url.indexOf('?');if(q>=0)for(String part:url.substring(q+1).split("[&#]")){var pair=part.split("=",2);if(pair.length==2)try{if(SENSITIVE.matcher(java.net.URLDecoder.decode(pair[0],java.nio.charset.StandardCharsets.UTF_8)).find())add(java.net.URLDecoder.decode(pair[1],java.nio.charset.StandardCharsets.UTF_8),secrets);}catch(IllegalArgumentException e){throw rejected();}}
            try{var uri=new java.net.URI(url);if(uri.getUserInfo()!=null)for(String v:uri.getUserInfo().split(":"))add(v,secrets);}catch(java.net.URISyntaxException ignored){/* URL templates are handled structurally. */}
        }
        if(value.isObject()&&value.path("auth").isObject())for(String field:List.of("token","username","password","value"))if(value.path("auth").path(field).isTextual())add(value.path("auth").path(field).asText(),secrets);
        if(value.isObject()&&(value.has("headers")||value.has("metadata")))for(String field:List.of("headers","metadata"))if(value.path(field).isArray())for(var h:value.path(field))if(SENSITIVE.matcher(h.path("name").asText(h.path("key").asText())).find()){
            String name=h.path("name").asText(h.path("key").asText()),v=h.path("value").asText();add(v,secrets);
            if(name.toLowerCase(Locale.ROOT).contains("cookie"))for(String part:v.split(";")){var pair=part.split("=",2);if(pair.length==2)add(pair[1].trim(),secrets);}
        }
    }
    private void secretLeaves(JsonNode value,Set<String> secrets){if(value.isContainerNode())for(var child:value)secretLeaves(child,secrets);else if(!value.isNull())add(value.asText(),secrets);}
    private void bounded(JsonNode root){var queue=new ArrayDeque<Map.Entry<JsonNode,Integer>>();queue.add(Map.entry(root,0));int count=0;while(!queue.isEmpty()){var next=queue.removeFirst();if(++count>20000||next.getValue()>32)throw rejected();if(next.getKey().isContainerNode())for(var node:next.getKey())queue.add(Map.entry(node,next.getValue()+1));}}
    private void add(String value,Set<String> secrets){if(!value.isBlank()&&!VARIABLE.matcher(value).matches()){secrets.add(value);if(value.matches("(?i)^(Bearer|Basic) .+"))secrets.add(value.substring(value.indexOf(' ')+1));}}
    private String placeholder(String field){return "{{SYNC_REVIEW_"+field.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]","_")+"}}";}
    private org.springframework.web.server.ResponseStatusException rejected(){return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"重放草稿结构无法安全处理，请在原端核对；本地原稿保留");}
}
