package io.testhub.platform;

import com.fasterxml.jackson.databind.JsonNode;
import io.testhub.platform.account.PlatformUserPrincipal;
import io.testhub.platform.audit.AuditService;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class P2R2AuditActionsFlowTests extends P1FlowTestSupport {
    @Autowired AuditService audit;
    void event(PlatformUserPrincipal a,String p,String action) {audit.record(a,access.context(a).organizations().getFirst().id(),p,action,"QA_RESOURCE",unique(),Map.of());}
    JsonNode actions(PlatformUserPrincipal a,String p,String suffix) throws Exception {
        var r=mvc.perform(get("/api/v1/audit-logs/actions?projectId="+p+suffix).with(user(a))).andReturn();assertEquals(200,r.getResponse().getStatus());return json.readTree(r.getResponse().getContentAsString()).path("data");
    }
    @Test @DisplayName("TC-P2R2-D04-01") void optionsDoNotShrinkWithActionFilter() throws Exception {
        var a=admin();String p=project(a);event(a,p,"QA_FIRST");event(a,p,"QA_SECOND");var all=actions(a,p,"");assertEquals(all,actions(a,p,"&action=QA_FIRST&limit=1"));assertTrue(all.toString().contains("QA_SECOND"));
    }
    @Test @DisplayName("TC-P2R2-D04-02") void rareActionOlderThanTheListLimitIsStillReachable() throws Exception {
        var a=admin();String p=project(a);event(a,p,"QA_OLDER_THAN_200");jdbc.sql("UPDATE audit_log SET created_at='2020-01-01 00:00:00' WHERE project_id=:p AND action='QA_OLDER_THAN_200'").param("p",p).update();for(int n=0;n<205;n++)event(a,p,"QA_RECENT");
        assertFalse(audit.recent(p,null,200).stream().anyMatch(e->e.action().equals("QA_OLDER_THAN_200")));assertTrue(actions(a,p,"").toString().contains("QA_OLDER_THAN_200"));
    }
    @Test @DisplayName("TC-P2R2-D04-03") void emptyResultsDoNotEraseDistinctSortedMetadata() throws Exception {
        var a=admin();String p=project(a);for(String action:List.of("QA_Z","QA_A","QA_Z"))event(a,p,action);assertTrue(audit.recent(p,"QA_ABSENT",100).isEmpty());var values=new ArrayList<String>();actions(a,p,"").forEach(v->values.add(v.asText()));assertEquals(values.stream().distinct().sorted().toList(),values);assertEquals(1,values.stream().filter("QA_Z"::equals).count());
    }
    @Test @DisplayName("TC-P2R2-D04-04") void metadataUsesTheExistingAdminAndProjectScope() throws Exception {
        var a=admin();var b=actor();String p=project(a),other=project(b);event(a,p,"QA_VISIBLE");event(b,other,"QA_OTHER_PROJECT");assertFalse(actions(a,p,"").toString().contains("QA_OTHER_PROJECT"));assertEquals(403,mvc.perform(get("/api/v1/audit-logs/actions").with(user(b))).andReturn().getResponse().getStatus());assertEquals(401,mvc.perform(get("/api/v1/audit-logs/actions")).andReturn().getResponse().getStatus());
    }
    @Test @DisplayName("TC-P2R2-D04-06") void metadataNeverContainsAuditDetailsAndLeavesRowLimitIntact() throws Exception {
        var a=admin();String p=project(a);event(a,p,"QA_A");event(a,p,"QA_B");actions(a,p,"").forEach(v->assertTrue(v.isTextual()));assertEquals(1,audit.recent(p,null,1).size());
    }
}
