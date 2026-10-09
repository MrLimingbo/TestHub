package io.testhub.platform.storage;

import com.github.f4b6a3.ulid.UlidCreator;
import io.testhub.platform.access.AccessService;
import io.testhub.platform.audit.AuditService;
import io.testhub.platform.security.AuthenticatedPrincipal;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Intent survives rollback/crash; its row lock protects an in-flight object from the recovery worker. */
@Service
public class FileTransferStorage {
    private final JdbcClient jdbc;
    private final FileObjectRepository files;
    private final ObjectStorage storage;
    private final AccessService access;
    private final AuditService audit;
    private final String bucket;
    private final TransactionTemplate independent;
    public FileTransferStorage(JdbcClient jdbc, FileObjectRepository files, ObjectStorage storage, AccessService access,
                               AuditService audit, PlatformTransactionManager manager, @Value("${testhub.storage.bucket}") String bucket) {
        this.jdbc=jdbc;this.files=files;this.storage=storage;this.access=access;this.audit=audit;this.bucket=bucket;
        independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(10);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public FileObject copy(AuthenticatedPrincipal principal, String projectId, String name, String type, byte[] bytes) {
        var project=access.requireProjectParameterWrite(principal,projectId,Set.of("SYSTEM_ADMIN","ORG_ADMIN","PROJECT_ADMIN","TEST_LEAD","TESTER"));
        if(name==null||name.isBlank()||name.length()>255||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl)
                ||bytes==null||bytes.length==0||bytes.length>20*1024*1024) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"迁入文件名称或大小无效。");
        try {if(type==null||type.length()>255||type.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException();MediaType.parseMediaType(type);}
        catch(IllegalArgumentException error){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"迁入文件类型无效。");}
        String id=UlidCreator.getUlid().toString(), key=projectId+"/"+id;
        independent.executeWithoutResult(status -> jdbc.sql("INSERT INTO file_transfer_intent(id,organization_id,project_id,bucket,object_key) VALUES(:id,:org,:project,:bucket,:key)")
                .param("id",id).param("org",project.organizationId()).param("project",projectId).param("bucket",bucket).param("key",key).update());
        if(lock(id)==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"文件准备已过期，请重新迁移。");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCompletion(int status){recover(id);}
        });
        try {
            storage.ensureBucket(bucket);
            storage.put(bucket,key,new ByteArrayInputStream(bytes),bytes.length,type);
            files.insert(id,project.organizationId(),projectId,key,name,type,bytes.length,principal.userId());
        }catch(Exception error){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"迁入文件存储失败，已安排回收。",error);}
        audit.record(principal,project.organizationId(),projectId,"FILE_TRANSFERRED","FILE_OBJECT",id,Map.of("name",name,"sizeBytes",bytes.length));
        return files.findById(projectId,id).orElseThrow();
    }
    private Intent lock(String id){
        return jdbc.sql("SELECT project_id,bucket,object_key FROM file_transfer_intent WHERE id=:id FOR UPDATE").param("id",id)
                .query((rs,row)->new Intent(rs.getString(1),rs.getString(2),rs.getString(3))).optional().orElse(null);
    }
    public void recover(String id){
        try {
            independent.executeWithoutResult(status->{
                var intent=lock(id);if(intent==null)return;
                if(files.findById(intent.project(),id).isEmpty()) {
                    try {storage.remove(intent.bucket(),intent.key());}
                    catch(Exception error){throw new IllegalStateException("文件回收暂不可用",error);}
                }
                jdbc.sql("DELETE FROM file_transfer_intent WHERE id=:id").param("id",id).update();
            });
        }catch(Exception error){
            // Keep the durable intent: a later worker retries, and a successful database commit is never undone.
            LoggerFactory.getLogger(FileTransferStorage.class).warn("文件迁移清理将重试，记录 {}，原因类型 {}",id,error.getClass().getSimpleName());
        }
    }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void recoverStale(){
        jdbc.sql("SELECT id FROM file_transfer_intent WHERE created_at<:cutoff ORDER BY created_at LIMIT 100")
                .param("cutoff",Timestamp.from(Instant.now().minusSeconds(3600))).query(String.class).list().forEach(this::recover);
    }
    private record Intent(String project,String bucket,String key){}
}
