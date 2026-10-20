package io.testhub.platform.functional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testhub.platform.shared.api.ApiError;
import io.testhub.platform.shared.api.RequestIdFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON bytes before Jackson allocates decoded binary attachments, including chunked requests. */
@Component
@Order(0)
public class TransferPayloadLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BODY=33*1024*1024;
    public static final String TOO_LARGE=io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_ATTRIBUTE;
    private final ObjectMapper json;
    public TransferPayloadLimitFilter(ObjectMapper json){this.json=json;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        return !"POST".equals(request.getMethod())||!request.getServletPath().matches("/api/v1/projects/[^/]+/(?:(?:functional|api-design|api-automation|mock|performance|ui-automation|mobile-automation|quality-report|tasks|project-settings|notification-templates)/transfer|(?:files|functional(?:/(?:governance|asset-tags|defects|defect-links|defect-workflow|defect-people|defect-execution|history))?|versions|api-design|api-automation/(?:assets|scenarios|history|triggers)|(?:performance|ui-automation|mobile-automation)/(?:history|artifacts)|mobile-automation/application-files|api-debug/grpc/descriptors|tasks|project-settings|notification-templates|mock|performance|ui-automation|mobile-automation)/sync)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        boolean functional=request.getServletPath().endsWith("/versions/sync")||request.getServletPath().matches(".*/functional(?:/(?:governance|asset-tags|defects|defect-links|defect-workflow|defect-people|defect-execution|history))?/sync");
        boolean fileSync=request.getServletPath().endsWith("/files/sync");
        boolean apiDesign=request.getServletPath().endsWith("/api-design/sync");
        boolean apiAutomationAssets=request.getServletPath().endsWith("/api-automation/assets/sync");
        boolean apiAutomationScenarios=request.getServletPath().endsWith("/api-automation/scenarios/sync");
        boolean settings=request.getServletPath().endsWith("/project-settings/sync");
        boolean notifications=request.getServletPath().endsWith("/notification-templates/sync");
        boolean mobile=request.getServletPath().endsWith("/mobile-automation/sync");
        boolean ui=request.getServletPath().endsWith("/ui-automation/sync");
        boolean performance=request.getServletPath().endsWith("/performance/sync");
        int limit=fileSync?io.testhub.platform.storage.ProjectFileSyncService.MAX_BODY:apiDesign?10*1024*1024:functional?FunctionalSyncService.MAX_BYTES:mobile?io.testhub.platform.mobileautomation.MobileAutomationSyncService.MAX_BYTES:ui?io.testhub.platform.uiautomation.UiAutomationSyncService.MAX_BYTES:performance?io.testhub.platform.performance.PerformanceSyncService.MAX_BYTES:request.getServletPath().endsWith("/mock/sync")?io.testhub.platform.mock.MockSyncService.MAX_BYTES:notifications?io.testhub.platform.notification.NotificationSyncService.MAX_BYTES:settings?io.testhub.platform.parameter.ProjectSettingsSyncService.MAX_BYTES:request.getServletPath().endsWith("/tasks/sync")?io.testhub.platform.execution.TaskSyncService.MAX_BYTES:MAX_BODY;
        String message=fileSync?"文件同步请求不能超过 29 MiB。":apiDesign?"接口设计同步请求不能超过 10 MiB。":functional?"功能测试同步请求不能超过 4 MiB。":mobile?"移动端同步请求不能超过 4 MiB。":ui?"Web UI 同步请求不能超过 4 MiB。":performance?"性能同步请求不能超过 4 MiB。":request.getServletPath().endsWith("/mock/sync")?"Mock 同步请求不能超过 4 MiB。":notifications?"通知同步请求不能超过 4 MiB。":settings?"配置同步请求不能超过 4 MiB。":limit==MAX_BODY?"迁移请求不能超过 33 MiB。":"任务同步请求不能超过 1 MiB。";
        if (apiAutomationAssets) { limit=io.testhub.platform.apiautomation.ApiAutomationAssetSyncService.MAX_BYTES; message="接口自动化资料同步请求不能超过 10 MiB。"; }
        if (apiAutomationScenarios) { limit=io.testhub.platform.apiautomation.ApiAutomationScenarioSyncService.MAX_BYTES; message="场景同步请求不能超过 10 MiB。"; }
        if (request.getServletPath().endsWith("/api-automation/history/sync")) { limit=io.testhub.platform.apiautomation.ApiAutomationHistorySyncService.MAX_BYTES; message="历史同步请求不能超过 4 MiB。"; }
        if (request.getServletPath().endsWith("/api-automation/triggers/sync")) { limit=io.testhub.platform.apiautomation.ApiAutomationTriggerSyncService.MAX_BYTES; message="触发配置同步请求不能超过 4 MiB。"; }
        if (request.getServletPath().endsWith("/performance/history/sync")) { limit=io.testhub.platform.performance.PerformanceHistorySyncService.MAX_BYTES; message="性能历史同步请求不能超过 4 MiB。"; }
        if (request.getServletPath().endsWith("/performance/artifacts/sync")) { limit=io.testhub.platform.performance.PerformanceArtifactSyncService.MAX_BODY; message="原始工件同步请求不能超过 29 MiB。"; }
        if (request.getServletPath().endsWith("/ui-automation/history/sync")) { limit=io.testhub.platform.uiautomation.UiAutomationHistorySyncService.MAX_BYTES; message="Web UI 历史同步请求不能超过 4 MiB。"; }
        if (request.getServletPath().endsWith("/ui-automation/artifacts/sync")) { limit=io.testhub.platform.uiautomation.UiAutomationArtifactSyncService.MAX_BODY; message="Web UI 报告产物同步请求不能超过 35 MiB。"; }
        if (request.getServletPath().endsWith("/mobile-automation/history/sync")) { limit=io.testhub.platform.mobileautomation.MobileAutomationHistorySyncService.MAX_BYTES; message="移动端历史同步请求不能超过 4 MiB。"; }
        if (request.getServletPath().endsWith("/mobile-automation/artifacts/sync")) { limit=io.testhub.platform.mobileautomation.MobileAutomationArtifactSyncService.MAX_BODY; message="移动端报告产物同步请求不能超过 35 MiB。"; }
        if (request.getServletPath().endsWith("/mobile-automation/application-files/sync")) { limit=io.testhub.platform.mobileautomation.MobileApplicationFileSyncService.MAX_BYTES; message="应用文件关联请求不能超过 1 MiB。"; }
        if (request.getServletPath().endsWith("/api-debug/grpc/descriptors/sync")) { limit=io.testhub.platform.apidebug.GrpcDescriptorSyncService.MAX_BODY; message="描述符同步请求不能超过 2 MiB。"; }
        final int bodyLimit=limit;
        final String bodyMessage=message;
        if(request.getContentLengthLong()>limit){
            response.setStatus(413);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(),new ApiError("REQUEST_REJECTED",message,(String)request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE),Map.of()));return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request){
            private ServletInputStream bounded;
            @Override public ServletInputStream getInputStream()throws IOException{
                if(bounded!=null)return bounded;
                var input=super.getInputStream();
                bounded=new ServletInputStream(){
                    private long count;
                    private void add(int n)throws IOException{if(n>0&&((count+=n)>bodyLimit)){request.setAttribute(TOO_LARGE,true);request.setAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE,bodyMessage);throw new IOException("Transfer body limit exceeded");}}
                    private void checkLimit()throws IOException{if(count>bodyLimit)throw new IOException("Transfer body limit exceeded");}
                    @Override public int read()throws IOException{checkLimit();int value=input.read();if(value!=-1)add(1);return value;}
                    @Override public int read(byte[] bytes,int offset,int length)throws IOException{checkLimit();int n=input.read(bytes,offset,(int)Math.min(length,bodyLimit-count+1));add(n);return n;}
                    @Override public boolean isFinished(){return input.isFinished();}
                    @Override public boolean isReady(){return input.isReady();}
                    @Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
                };return bounded;
            }
            @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
