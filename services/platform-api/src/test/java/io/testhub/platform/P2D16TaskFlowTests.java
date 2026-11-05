package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.execution.ExecutionTaskService;
import io.testhub.platform.execution.TaskSyncService;
import io.testhub.platform.execution.TaskTransferService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class P2D16TaskFlowTests extends P1FlowTestSupport {
    @Autowired ExecutionTaskService tasks;
    @Autowired TaskTransferService transfer;
    @Autowired TaskSyncService sync;
    record Fixture(PlatformUserPrincipal actor, String project) { }
    Fixture fixture() { var a=actor(); return new Fixture(a,project(a)); }
    String base(Fixture f) { return "/api/v1/projects/"+f.project+"/tasks"; }
    String update(String status,String progress,long version) { return "{\"name\":\"QA\",\"status\":\""+status+"\","+(progress==null?"":"\"progress\":"+progress+",")+"\"expectedVersion\":"+version+"}"; }
    long count(String table) { return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single(); }
    Map<String,Long> counts() { return Map.of("tasks",count("execution_task"),"audit",count("audit_log"),"transfer",count("task_transfer_receipt"),"sync",count("task_sync_receipt")); }
    int sendPut(Fixture f,String id,String content)throws Exception { return mvc.perform(put(base(f)+"/"+id).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus(); }
    int sendPost(Fixture f,String path,String content)throws Exception { return mvc.perform(post(base(f)+path).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(content)).andReturn().getResponse().getStatus(); }

    @Test void exactValuesAndStatusNormalization() throws Exception {
        var f=fixture(); var t=tasks.create(f.actor,f.project,new ExecutionTaskService.TaskInput("API","QA"));
        for(String progress:List.of("0","100","10.0","1e1")) {
            assertEquals(200,sendPut(f,t.id(),update("RUNNING",progress,t.version())));
            t=tasks.list(f.actor,f.project).getFirst(); assertEquals((int)Double.parseDouble(progress),t.progress());
        }
        assertEquals(200,sendPut(f,t.id(),update("QUEUED","10.0",t.version()))); t=tasks.list(f.actor,f.project).getFirst();assertEquals(0,t.progress());
        assertEquals(200,sendPut(f,t.id(),update("SUCCEEDED","10",t.version()))); t=tasks.list(f.actor,f.project).getFirst();assertEquals(100,t.progress());
    }
    @Test void invalidInputNeverChangesTaskOrAuditEvenForNormalizedStates() throws Exception {
        var f=fixture(); var t=tasks.create(f.actor,f.project,new ExecutionTaskService.TaskInput("API","QA"));
        for(String state:List.of("RUNNING","QUEUED","SUCCEEDED")) for(String v:new String[]{null,"null","-1","101","1.5","1.0000000000000001","1e10000","2147483648","\"10\"","true","[]","{}"}) {
            var before=counts(); assertEquals(400,sendPut(f,t.id(),update(state,v,t.version())),state+" "+v); assertEquals(before,counts()); assertEquals(t,tasks.list(f.actor,f.project).getFirst());
        }
    }
    @Test void versionAndProjectProtectionRemain() throws Exception {
        var f=fixture();var other=fixture();var t=tasks.create(f.actor,f.project,new ExecutionTaskService.TaskInput("API","QA"));
        assertEquals(409,sendPut(f,t.id(),update("RUNNING","10",t.version()+1)));
        assertEquals(403,mvc.perform(put(base(f)+"/"+t.id()).with(user(other.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(update("RUNNING","10",t.version()))).andReturn().getResponse().getStatus());
        assertEquals(t,tasks.list(f.actor,f.project).getFirst());
    }
    @Test void transferInputIsExactAndAtomic() throws Exception {
        var source=fixture();var target=fixture(); var t=tasks.create(source.actor,source.project,new ExecutionTaskService.TaskInput("API","QA"));
        tasks.update(source.actor,source.project,t.id(),new ExecutionTaskService.TaskUpdate("QA","RUNNING",10,t.version()));
        var snapshot=transfer.export(source.actor,source.project);
        for(String bad:new String[]{null,"null","-1","101","1e10000","NaN","Infinity","-Infinity","1.5","10.0000000000000001","\"10\"","true","[]","{}"}) {
            String raw=json.writeValueAsString(new TaskTransferService.ImportRequest(UUID.randomUUID().toString(),target.actor.userId(),snapshot));
            String payload=bad==null?raw.replace(",\"progress\":10",""):raw.replace("\"progress\":10","\"progress\":"+bad); assertNotEquals(raw,payload); var before=counts();assertEquals(400,sendPost(target,"/transfer",payload));assertEquals(before,counts());
        }
        for(String good:List.of("10","10.0","1e1")) {
            var dest=fixture();String raw=json.writeValueAsString(new TaskTransferService.ImportRequest(UUID.randomUUID().toString(),dest.actor.userId(),snapshot));
            assertEquals(200,sendPost(dest,"/transfer",raw.replace("\"progress\":10","\"progress\":"+good)));assertEquals(10,tasks.list(dest.actor,dest.project).getFirst().progress());
        }
    }
    @Test void syncInputIsExactAndAtomic() throws Exception {
        var f=fixture();
        for(String bad:new String[]{null,"null","-1","101","1e10000","NaN","Infinity","-Infinity","1.5","10.0000000000000001","\"10\"","true","[]","{}"}) {
            var op=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","QA","RUNNING",10);
            String raw=json.writeValueAsString(new TaskSyncService.Request(UUID.randomUUID().toString(),f.actor.userId(),List.of(op)));
            String payload=bad==null?raw.replace(",\"progress\":10",""):raw.replace("\"progress\":10","\"progress\":"+bad);assertNotEquals(raw,payload);var before=counts();assertEquals(400,sendPost(f,"/sync",payload));assertEquals(before,counts());
        }
        for(String good:List.of("10","10.0","1e1")) {
            var op=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","QA","RUNNING",10);
            String raw=json.writeValueAsString(new TaskSyncService.Request(UUID.randomUUID().toString(),f.actor.userId(),List.of(op)));
            assertEquals(200,sendPost(f,"/sync",raw.replace("\"progress\":10","\"progress\":"+good)));
        }
        assertEquals(3,tasks.list(f.actor,f.project).size());assertTrue(tasks.list(f.actor,f.project).stream().allMatch(x->x.progress()==10));
    }
    @Test void progressErrorLocatesOnlyFixedFieldWithoutEchoingInput() throws Exception {
        var f=fixture();var t=tasks.create(f.actor,f.project,new ExecutionTaskService.TaskInput("API","QA"));
        var response=mvc.perform(put(base(f)+"/"+t.id()).with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(update("RUNNING","{\"PRIVATE_MARKER\":true}",t.version()))).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsString().contains("progress"));assertFalse(response.getContentAsString().contains("PRIVATE_MARKER"));
    }
    @Test void transferAndSyncBoundariesAndStatusConstraints() throws Exception {
        var source=fixture();var t=tasks.create(source.actor,source.project,new ExecutionTaskService.TaskInput("API","QA"));
        tasks.update(source.actor,source.project,t.id(),new ExecutionTaskService.TaskUpdate("QA","RUNNING",10,t.version()));
        var snapshot=transfer.export(source.actor,source.project);
        for(String[] sample:List.of(new String[]{"RUNNING","0","200"},new String[]{"RUNNING","100","200"},new String[]{"QUEUED","0","200"},new String[]{"SUCCEEDED","100","200"},new String[]{"QUEUED","1","400"},new String[]{"SUCCEEDED","99","400"})) {
            var dest=fixture();String raw=json.writeValueAsString(new TaskTransferService.ImportRequest(UUID.randomUUID().toString(),dest.actor.userId(),snapshot));
            String body=raw.replace("\"status\":\"RUNNING\"","\"status\":\""+sample[0]+"\"").replace("\"progress\":10","\"progress\":"+sample[1]);
            var before=counts();assertEquals(Integer.parseInt(sample[2]),sendPost(dest,"/transfer",body));
            if(sample[2].equals("400")) assertEquals(before,counts());
            else assertEquals(Integer.parseInt(sample[1]),tasks.list(dest.actor,dest.project).getFirst().progress());
            var op=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","QA",sample[0],Integer.parseInt(sample[1]));
            String syncBody=json.writeValueAsString(new TaskSyncService.Request(UUID.randomUUID().toString(),dest.actor.userId(),List.of(op)));
            before=counts();assertEquals(Integer.parseInt(sample[2]),sendPost(dest,"/sync",syncBody));
            if(sample[2].equals("400")) assertEquals(before,counts());
            else assertTrue(tasks.list(dest.actor,dest.project).stream().anyMatch(task->task.status().equals(sample[0]) && task.progress()==Integer.parseInt(sample[1])));
        }
    }
    @Test void syncBatchRejectsInvalidLaterOperationWithoutPartialWrites() throws Exception {
        var f=fixture();var first=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","first","RUNNING",10);
        var second=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","second","RUNNING",20);
        String raw=json.writeValueAsString(new TaskSyncService.Request(UUID.randomUUID().toString(),f.actor.userId(),List.of(first,second)));
        String body=raw.replace("\"progress\":20","\"progress\":20.5");assertNotEquals(raw,body);var before=counts();
        var response=mvc.perform(post(base(f)+"/sync").with(user(f.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsString().contains("operations[1].progress"));assertEquals(before,counts());
    }
    @Test void transferAndSyncTypeErrorsLocateIndicesWithoutEchoingPayload() throws Exception {
        var source=fixture();var dest=fixture();var t=tasks.create(source.actor,source.project,new ExecutionTaskService.TaskInput("API","QA"));
        tasks.update(source.actor,source.project,t.id(),new ExecutionTaskService.TaskUpdate("QA","RUNNING",10,t.version()));
        String raw=json.writeValueAsString(new TaskTransferService.ImportRequest(UUID.randomUUID().toString(),dest.actor.userId(),transfer.export(source.actor,source.project)));
        String body=raw.replace("\"progress\":10","\"progress\":{\"PRIVATE_MARKER\":true}");assertNotEquals(raw,body);
        var response=mvc.perform(post(base(dest)+"/transfer").with(user(dest.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsString().contains("snapshot.tasks[0].progress"));assertFalse(response.getContentAsString().contains("PRIVATE_MARKER"));
        var op=new TaskSyncService.Operation(UUID.randomUUID().toString(),null,null,"API","QA","RUNNING",10);
        raw=json.writeValueAsString(new TaskSyncService.Request(UUID.randomUUID().toString(),dest.actor.userId(),List.of(op)));
        body=raw.replace("\"progress\":10","\"progress\":{\"PRIVATE_MARKER\":true}");assertNotEquals(raw,body);
        response=mvc.perform(post(base(dest)+"/sync").with(user(dest.actor)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(400,response.getStatus());assertTrue(response.getContentAsString().contains("operations[0].progress"));assertFalse(response.getContentAsString().contains("PRIVATE_MARKER"));
    }
}
