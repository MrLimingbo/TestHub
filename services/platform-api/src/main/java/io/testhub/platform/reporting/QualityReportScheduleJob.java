package io.testhub.platform.reporting;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class QualityReportScheduleJob implements Job {
    @Autowired
    private QualityReportScheduleService schedules;

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String scheduleId = context.getMergedJobDataMap().getString("scheduleId");
        try {
            schedules.executeQuartzJob(scheduleId);
        } catch (Exception exception) {
            throw new JobExecutionException("质量报告定时任务触发失败", exception, false);
        }
    }
}
