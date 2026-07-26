package io.testhub.platform.apiautomation;

import org.quartz.spi.JobFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration
public class QuartzAutomationScheduleConfiguration {
    @Bean
    SchedulerFactoryBeanCustomizer automationScheduleJobFactory(AutowireCapableBeanFactory beanFactory) {
        JobFactory jobFactory = new SpringBeanJobFactory() {
            @Override
            protected Object createJobInstance(org.quartz.spi.TriggerFiredBundle bundle) throws Exception {
                Object job = super.createJobInstance(bundle);
                beanFactory.autowireBean(job);
                return job;
            }
        };
        return schedulerFactory -> schedulerFactory.setJobFactory(jobFactory);
    }
}
