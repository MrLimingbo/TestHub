package io.testhub.platform;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.functional.TransferPayloadLimitFilter;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TransferPayloadLimitFilterTests {
    private final TransferPayloadLimitFilter filter = new TransferPayloadLimitFilter(new ObjectMapper());
    @Test void performanceArtifactsBoundDeclaredAndChunkedBodiesAtTwentyNineMiB() throws Exception {
        int limit = io.testhub.platform.performance.PerformanceArtifactSyncService.MAX_BODY;
        String route = "/api/v1/projects/fixture/performance/artifacts/sync";
        var exact = streamed(limit); exact.setServletPath(route);
        filter.doFilter(exact, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(limit, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked = streamed(limit + 1L); chunked.setServletPath(route);
        filter.doFilter(chunked, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("原始工件同步请求不能超过 29 MiB。", chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath(route); var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Must reject before consuming body")); assertEquals(413, response.getStatus());
    }
    @Test void performanceHistorySyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit = io.testhub.platform.performance.PerformanceHistorySyncService.MAX_BYTES;
        String route = "/api/v1/projects/fixture/performance/history/sync";
        var exact = streamed(limit); exact.setServletPath(route);
        filter.doFilter(exact, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(limit, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked = streamed(limit + 1L); chunked.setServletPath(route);
        filter.doFilter(chunked, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("性能历史同步请求不能超过 4 MiB。", chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath(route); var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Must reject before consuming body")); assertEquals(413, response.getStatus());
    }
    @Test void automationScenarioSyncBoundsDeclaredAndChunkedBodiesAtTenMiB() throws Exception {
        int limit = io.testhub.platform.apiautomation.ApiAutomationScenarioSyncService.MAX_BYTES;
        String route = "/api/v1/projects/fixture/api-automation/scenarios/sync";
        var exact = streamed(limit); exact.setServletPath(route);
        filter.doFilter(exact, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(limit, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked = streamed(limit + 1L); chunked.setServletPath(route);
        filter.doFilter(chunked, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("场景同步请求不能超过 10 MiB。", chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath(route); var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Must reject before consuming body")); assertEquals(413, response.getStatus());
    }
    @Test void automationAssetSyncBoundsDeclaredAndChunkedBodiesAtTenMiB() throws Exception {
        int limit = io.testhub.platform.apiautomation.ApiAutomationAssetSyncService.MAX_BYTES;
        String route = "/api/v1/projects/fixture/api-automation/assets/sync";
        var exact = streamed(limit); exact.setServletPath(route);
        filter.doFilter(exact, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(limit, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked = streamed(limit + 1L); chunked.setServletPath(route);
        filter.doFilter(chunked, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("接口自动化资料同步请求不能超过 10 MiB。", chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath(route); var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Must reject before consuming body"));
        assertEquals(413, response.getStatus());
    }
    @Test void projectVersionSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.version.ProjectVersionSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/versions/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/versions/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/versions/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalDefectSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalDefectSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/defects/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/defects/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/defects/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalDefectWorkflowSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalDefectWorkflowSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/defect-workflow/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/defect-workflow/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/defect-workflow/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalDefectPeopleSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalDefectPeopleSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/defect-people/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/defect-people/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/defect-people/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalDefectExecutionSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalDefectExecutionSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/defect-execution/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/defect-execution/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/defect-execution/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }    @Test void functionalHistorySyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalHistorySyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/history/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/history/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/history/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalDefectLinkSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalDefectLinkSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/defect-links/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/defect-links/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/defect-links/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalAssetTagSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalAssetTagSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/asset-tags/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/asset-tags/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/asset-tags/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalGovernanceSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalGovernanceSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/governance/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/governance/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/governance/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void functionalSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.functional.FunctionalSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/functional/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/functional/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("功能测试同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/functional/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void mobileAutomationSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.mobileautomation.MobileAutomationSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/mobile-automation/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/mobile-automation/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("移动端同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/mobile-automation/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void uiAutomationSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.uiautomation.UiAutomationSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/ui-automation/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/ui-automation/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("Web UI 同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/ui-automation/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void performanceSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.performance.PerformanceSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/performance/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/performance/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("性能同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/performance/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void mockSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.mock.MockSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/mock/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/mock/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("Mock 同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/mock/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void notificationSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.notification.NotificationSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/notification-templates/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/notification-templates/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("通知同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/notification-templates/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void settingsSyncBoundsDeclaredAndChunkedBodiesAtFourMiB() throws Exception {
        int limit=io.testhub.platform.parameter.ProjectSettingsSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/project-settings/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/project-settings/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("配置同步请求不能超过 4 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/project-settings/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }
    @Test void taskSyncHasItsOwnOneMiBLimitForDeclaredAndChunkedBodies() throws Exception {
        int limit=io.testhub.platform.execution.TaskSyncService.MAX_BYTES;
        var exact=streamed(limit);exact.setServletPath("/api/v1/projects/fixture/tasks/sync");
        filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var chunked=streamed(limit+1L);chunked.setServletPath("/api/v1/projects/fixture/tasks/sync");
        filter.doFilter(chunked,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertEquals("任务同步请求不能超过 1 MiB。",chunked.getAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};
        declared.setMethod("POST");declared.setServletPath("/api/v1/projects/fixture/tasks/sync");var response=new MockHttpServletResponse();
        filter.doFilter(declared,response,(req,res)->fail("Must reject before consuming body"));assertEquals(413,response.getStatus());
    }

    private MockHttpServletRequest streamed(long size) {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return -1; }
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    long remaining = size;
                    @Override public int read() { return remaining-- > 0 ? 0 : -1; }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        if (length == 0) return 0;
                        if (remaining <= 0) return -1;
                        int count = (int) Math.min(length, remaining);
                        java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0);
                        remaining -= count;
                        return count;
                    }
                    @Override public boolean isFinished() { return remaining <= 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        };
        request.setMethod("POST");
        request.setServletPath("/api/v1/projects/fixture/functional/transfer");
        return request;
    }

    @Test void chunkedOversizeStopsReadingAndRemainsRejectedOnRetry() throws Exception {
        var request = streamed(TransferPayloadLimitFilter.MAX_BODY + 100L);
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            var input = wrapped.getInputStream();
            assertSame(input, wrapped.getInputStream());
            assertThrows(IOException.class, () -> input.transferTo(OutputStream.nullOutputStream()));
            assertEquals(true, request.getAttribute(TransferPayloadLimitFilter.TOO_LARGE));
            assertThrows(IOException.class, input::read);
            assertThrows(IOException.class, () -> input.read(new byte[4]));
        });
    }

    @Test void exactLimitSucceedsAndUnrelatedRoutesAreUntouched() throws Exception {
        var request = streamed(TransferPayloadLimitFilter.MAX_BODY);
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(TransferPayloadLimitFilter.MAX_BODY, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        assertNull(request.getAttribute(TransferPayloadLimitFilter.TOO_LARGE));
        request = streamed(TransferPayloadLimitFilter.MAX_BODY + 1L);
        request.setServletPath("/api/v1/projects/fixture/files");
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) ->
                assertEquals(TransferPayloadLimitFilter.MAX_BODY + 1L, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
    }

    @Test void declaredOversizeUsesSharedErrorEnvelopeWithoutConsumingBody() throws Exception {
        var request = new MockHttpServletRequest() {
            @Override public long getContentLengthLong() { return TransferPayloadLimitFilter.MAX_BODY + 1L; }
            @Override public ServletInputStream getInputStream() { fail("Oversized body must not be consumed"); return null; }
        };
        request.setMethod("POST"); request.setServletPath("/api/v1/projects/fixture/functional/transfer");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("Oversized request must not reach controller"));
        assertEquals(413, response.getStatus());
        assertEquals("REQUEST_REJECTED", new ObjectMapper().readTree(response.getContentAsByteArray()).path("code").asText());
    }
    @Test void apiTransferUsesSameDeclaredAndChunkedLimitsWithoutAffectingOtherApiRoutes() throws Exception {
        for (String module : new String[]{"api-design", "api-automation", "mock", "performance", "ui-automation", "mobile-automation", "quality-report", "tasks", "project-settings", "notification-templates"}) {
            var request = streamed(TransferPayloadLimitFilter.MAX_BODY + 1L);
            request.setServletPath("/api/v1/projects/fixture/" + module + "/transfer");
            filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream()));
                assertEquals(true, request.getAttribute(TransferPayloadLimitFilter.TOO_LARGE));
            });
            var declared = new MockHttpServletRequest() {
                @Override public long getContentLengthLong() { return TransferPayloadLimitFilter.MAX_BODY + 1L; }
            };
            declared.setMethod("POST"); declared.setServletPath("/api/v1/projects/fixture/" + module + "/transfer");
            var response = new MockHttpServletResponse();
            filter.doFilter(declared, response, (req, res) -> fail("Oversized API transfer must not reach controller"));
            assertEquals(413, response.getStatus());
        }
        var unrelated = streamed(TransferPayloadLimitFilter.MAX_BODY + 1L);
        unrelated.setServletPath("/api/v1/projects/fixture/api-design/definitions");
        filter.doFilter(unrelated, new MockHttpServletResponse(), (wrapped, res) ->
                assertEquals(TransferPayloadLimitFilter.MAX_BODY + 1L, wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
    }

    @Test void apiAutomationHistorySyncUsesDeclaredAndChunkedFourMiBLimit() throws Exception {
        int limit = io.testhub.platform.apiautomation.ApiAutomationHistorySyncService.MAX_BYTES;
        var streamed = streamed(limit + 1L); streamed.setServletPath("/api/v1/projects/fixture/api-automation/history/sync");
        filter.doFilter(streamed, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath("/api/v1/projects/fixture/api-automation/history/sync");
        var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Oversized history must not reach controller"));
        assertEquals(413, response.getStatus());
    }
    @Test void apiAutomationTriggerSyncUsesDeclaredAndChunkedFourMiBLimit() throws Exception {
        int limit = io.testhub.platform.apiautomation.ApiAutomationTriggerSyncService.MAX_BYTES;
        var streamed = streamed(limit + 1L); streamed.setServletPath("/api/v1/projects/fixture/api-automation/triggers/sync");
        filter.doFilter(streamed, new MockHttpServletResponse(), (wrapped, response) ->
                assertThrows(IOException.class, () -> wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var declared = new MockHttpServletRequest() { @Override public long getContentLengthLong() { return limit + 1L; } };
        declared.setMethod("POST"); declared.setServletPath("/api/v1/projects/fixture/api-automation/triggers/sync");
        var response = new MockHttpServletResponse();
        filter.doFilter(declared, response, (req, res) -> fail("Oversized triggers must not reach controller"));
        assertEquals(413, response.getStatus());
    }

    @Test void webUiHistoryAndArtifactRoutesUseTheirDeclaredAndStreamedLimits() throws Exception {
        for(var row:java.util.Map.of("history",4*1024*1024,"artifacts",35*1024*1024).entrySet()){
            int limit=row.getValue();String route="/api/v1/projects/fixture/ui-automation/"+row.getKey()+"/sync";
            var stream=streamed(limit+1L);stream.setServletPath(route);
            filter.doFilter(stream,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
            var exact=streamed(limit);exact.setServletPath(route);filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
            var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};declared.setMethod("POST");declared.setServletPath(route);
            var response=new MockHttpServletResponse();filter.doFilter(declared,response,(wrapped,res)->fail("Oversized UI sync must not reach controller"));assertEquals(413,response.getStatus());
        }
    }
    @Test void mobileHistoryAndArtifactRoutesUseTheirDeclaredAndStreamedLimits() throws Exception {
        for(var row:java.util.Map.of("history",4*1024*1024,"artifacts",35*1024*1024).entrySet()){
            int limit=row.getValue();String route="/api/v1/projects/fixture/mobile-automation/"+row.getKey()+"/sync";
            var stream=streamed(limit+1L);stream.setServletPath(route);
            filter.doFilter(stream,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
            var exact=streamed(limit);exact.setServletPath(route);filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
            var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};declared.setMethod("POST");declared.setServletPath(route);
            var response=new MockHttpServletResponse();filter.doFilter(declared,response,(wrapped,res)->fail("Oversized mobile sync must not reach controller"));assertEquals(413,response.getStatus());
        }
    }

    @Test void mobileApplicationFileAssociationBoundsDeclaredAndChunkedBodies() throws Exception {
        int limit=1024*1024;String route="/api/v1/projects/fixture/mobile-automation/application-files/sync";
        var stream=streamed(limit+1L);stream.setServletPath(route);filter.doFilter(stream,new MockHttpServletResponse(),(wrapped,response)->assertThrows(IOException.class,()->wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var exact=streamed(limit);exact.setServletPath(route);filter.doFilter(exact,new MockHttpServletResponse(),(wrapped,response)->assertEquals(limit,wrapped.getInputStream().transferTo(OutputStream.nullOutputStream())));
        var declared=new MockHttpServletRequest(){@Override public long getContentLengthLong(){return limit+1L;}};declared.setMethod("POST");declared.setServletPath(route);var response=new MockHttpServletResponse();filter.doFilter(declared,response,(wrapped,res)->fail("Oversized association request must not reach controller"));assertEquals(413,response.getStatus());
    }

}
