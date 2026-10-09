package io.testhub.platform.functional;

import io.testhub.platform.security.AuthenticatedPrincipal;
import io.testhub.platform.storage.FileObjectService;
import io.testhub.platform.storage.FileTransferStorage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FunctionalTransferFiles {
    public static final int MAX_BYTES=20*1024*1024;
    private final FileObjectService files;
    private final FileTransferStorage storage;
    public FunctionalTransferFiles(FileObjectService files,FileTransferStorage storage){this.files=files;this.storage=storage;}
    public Definitions export(AuthenticatedPrincipal principal,String project){
        var metadata=files.list(principal,project);
        if(metadata.size()>100||metadata.stream().mapToLong(item->item.sizeBytes()).sum()>MAX_BYTES)throw invalid("单次最多迁移 100 个项目文件，合计 20 MiB。");
        List<File> result=new ArrayList<>();
        for(var item:metadata){
            if(item.sizeBytes()<1||item.sizeBytes()>MAX_BYTES)throw invalid("项目文件大小不支持迁移。");
            try(var stream=files.download(principal,project,item.id())){
                byte[] bytes=stream.readNBytes((int)item.sizeBytes()+1);
                if(bytes.length!=item.sizeBytes())throw invalid("项目文件内容与记录大小不一致，请修复后重试。");
                result.add(new File(item.id(),item.originalName(),item.contentType(),item.sizeBytes(),sha256(bytes),bytes));
            }catch(java.io.IOException error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"项目文件读取失败。",error);}
        }
        return new Definitions(project,result);
    }
    public void validate(Definitions data,Set<String> ids){
        if(data==null||data.projectId()==null||!data.projectId().matches("[A-Z0-9]{26}")||data.items()==null||data.items().size()>100)throw invalid("项目文件资料不完整或超过 100 个。");
        long bytes=0;
        for(var file:data.items()){
            if(file==null||file.id()==null||!file.id().matches("[A-Z0-9]{26}")||!ids.add(file.id())||file.content()==null
                    ||file.size()<1||file.size()!=file.content().length||!sha256(file.content()).equals(file.sha256()))throw invalid("项目文件标识、大小或 SHA-256 校验失败。");
            bytes+=file.size();if(bytes>MAX_BYTES)throw invalid("项目文件合计不能超过 20 MiB。");
        }
    }
    public Map<String,String> transfer(AuthenticatedPrincipal principal,String project,Definitions data){
        Map<String,String> result=new HashMap<>();
        for(var file:data.items()){
            var copied=storage.copy(principal,project,file.name(),file.type(),file.content());
            result.put(file.id(),"/api/v1/projects/"+project+"/files/"+copied.id()+"/download");
        }
        return result;
    }
    public String rewrite(String text,Definitions data,Map<String,String> replacements){
        if(text==null||data==null)return text;
        // Only canonical root-relative links: never rewrite a suffix inside an absolute/external URL.
        var pattern=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_:/%.\\\\-])/api/v1/projects/"+java.util.regex.Pattern.quote(data.projectId())+"/files/([A-Z0-9]{26})/download(?=$|[\\s\\\"'<>?#)\\]])");
        var matcher=pattern.matcher(text);StringBuilder result=new StringBuilder();
        while(matcher.find()){
            String replacement=replacements.get(matcher.group(1));
            if(replacement==null)throw invalid("正文引用的项目文件不在本次迁移资料中。");
            matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);return result.toString();
    }
    public FunctionalTransferService.Snapshot rewriteSnapshot(FunctionalTransferService.Snapshot source,Map<String,String> paths){
        java.util.function.UnaryOperator<String> text=value->rewrite(value,source.files(),paths);
        var requirements=source.requirements().stream().map(item->new FunctionalTransferService.Requirement(item.id(),item.catalogId(),item.code(),item.title(),item.priority(),item.status(),text.apply(item.description()))).toList();
        var cases=source.cases().stream().map(item->new FunctionalTransferService.Case(item.id(),item.catalogId(),item.requirementId(),item.code(),item.title(),item.priority(),item.status(),item.caseType(),text.apply(item.precondition()),text.apply(item.steps()),text.apply(item.expectedResult()))).toList();
        var plans=source.plans()==null?null:source.plans().stream().map(item->new FunctionalTransferService.Plan(item.id(),item.catalogId(),item.code(),item.name(),item.status(),text.apply(item.description()),item.caseIds())).toList();
        var defects=source.defects()==null?null:new FunctionalTransferDefects.Definitions(source.defects().items().stream().map(item->new FunctionalTransferDefects.Defect(item.id(),item.catalogId(),item.code(),item.title(),item.dueDate(),item.bugType(),item.keywords(),item.browsers(),item.operatingSystems(),item.severity(),item.priority(),item.status(),item.confirmed(),item.resolution(),item.source(),item.externalUrl(),text.apply(item.description()),item.links(),item.omittedExecutionLinks())).toList());
        return new FunctionalTransferService.Snapshot(source.version(),source.catalogs(),requirements,cases,plans,source.governance(),defects,source.files(),FunctionalTransferHistory.rewrite(source.history(),text),FunctionalTransferVersions.rewrite(source.projectVersions(),text));
    }
    public static String sha256(byte[] bytes){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private ResponseStatusException invalid(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    public record Definitions(@NotBlank @Pattern(regexp="[A-Z0-9]{26}") String projectId,@NotNull @Size(max=100) List<@NotNull @Valid File> items){}
    public record File(@NotBlank @Pattern(regexp="[A-Z0-9]{26}") String id,@NotBlank @Size(max=255) String name,@NotBlank @Size(max=255) String type,
                       @Min(1) @Max(MAX_BYTES) long size,@NotBlank @Pattern(regexp="[a-f0-9]{64}") String sha256,@NotNull @Size(min=1,max=MAX_BYTES) byte[] content){}
    public record Counts(int items,long bytes){}
}
