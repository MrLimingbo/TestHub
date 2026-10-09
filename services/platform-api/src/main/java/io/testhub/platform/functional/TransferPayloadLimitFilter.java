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
        return !"POST".equals(request.getMethod())||!request.getServletPath().matches("/api/v1/projects/[^/]+/(?:(?:functional|api-design|api-automation|mock|performance|ui-automation|mobile-automation|quality-report|tasks|project-settings|notification-templates)/transfer|(?:tasks|project-settings|notification-templates|mock|performance|ui-automation|mobile-automation)/sync)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        boolean settings=request.getServletPath().endsWith("/project-settings/sync");
        boolean notifications=request.getServletPath().endsWith("/notification-templates/sync");
        boolean mobile=request.getServletPath().endsWith("/mobile-automation/sync");
        boolean ui=request.getServletPath().endsWith("/ui-automation/sync");
        boolean performance=request.getServletPath().endsWith("/performance/sync");
        int limit=mobile?io.testhub.platform.mobileautomation.MobileAutomationSyncService.MAX_BYTES:ui?io.testhub.platform.uiautomation.UiAutomationSyncService.MAX_BYTES:performance?io.testhub.platform.performance.PerformanceSyncService.MAX_BYTES:request.getServletPath().endsWith("/mock/sync")?io.testhub.platform.mock.MockSyncService.MAX_BYTES:notifications?io.testhub.platform.notification.NotificationSyncService.MAX_BYTES:settings?io.testhub.platform.parameter.ProjectSettingsSyncService.MAX_BYTES:request.getServletPath().endsWith("/tasks/sync")?io.testhub.platform.execution.TaskSyncService.MAX_BYTES:MAX_BODY;
        String message=mobile?"移动端同步请求不能超过 4 MiB。":ui?"Web UI 同步请求不能超过 4 MiB。":performance?"性能同步请求不能超过 4 MiB。":request.getServletPath().endsWith("/mock/sync")?"Mock 同步请求不能超过 4 MiB。":notifications?"通知同步请求不能超过 4 MiB。":settings?"配置同步请求不能超过 4 MiB。":limit==MAX_BODY?"迁移请求不能超过 33 MiB。":"任务同步请求不能超过 1 MiB。";
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
                    private void add(int n)throws IOException{if(n>0&&((count+=n)>limit)){request.setAttribute(TOO_LARGE,true);request.setAttribute(io.testhub.platform.shared.api.ApiExceptionHandler.BODY_TOO_LARGE_MESSAGE_ATTRIBUTE,message);throw new IOException("Transfer body limit exceeded");}}
                    private void checkLimit()throws IOException{if(count>limit)throw new IOException("Transfer body limit exceeded");}
                    @Override public int read()throws IOException{checkLimit();int value=input.read();if(value!=-1)add(1);return value;}
                    @Override public int read(byte[] bytes,int offset,int length)throws IOException{checkLimit();int n=input.read(bytes,offset,(int)Math.min(length,limit-count+1));add(n);return n;}
                    @Override public boolean isFinished(){return input.isFinished();}
                    @Override public boolean isReady(){return input.isReady();}
                    @Override public void setReadListener(ReadListener listener){input.setReadListener(listener);}
                };return bounded;
            }
            @Override public BufferedReader getReader()throws IOException{return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
}
