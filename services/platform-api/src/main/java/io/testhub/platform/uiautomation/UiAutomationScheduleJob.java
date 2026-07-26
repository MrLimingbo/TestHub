package io.testhub.platform.uiautomation;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class UiAutomationScheduleJob implements Job {
    @Autowired private UiAutomationScheduleService schedules;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try { schedules.executeQuartzJob(context.getMergedJobDataMap().getString("scheduleId")); }
        catch (Exception exception) { throw new JobExecutionException("Web UI 自动化定时任务触发失败", exception, false); }
    }
}
