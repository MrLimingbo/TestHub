package io.testhub.platform.apiautomation;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

/** Quartz only carries a schedule id; scenario data and variable values never enter JobDataMap. */
@DisallowConcurrentExecution
public class ApiAutomationScheduleJob implements Job {
    @Autowired
    private ApiAutomationScheduleService schedules;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String scheduleId = context.getMergedJobDataMap().getString("scheduleId");
        try {
            schedules.executeQuartzJob(scheduleId);
        } catch (Exception exception) {
            throw new JobExecutionException("接口自动化定时任务触发失败", exception, false);
        }
    }
}
