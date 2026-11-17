package ai.codecraft.modules.workspace.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class WorkspaceSchedulingConfiguration {
    /** Fixed scheduled jobs only; model/network work remains in bounded worker executors. */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("task-discovery-", 4); }

    @Bean
    public ThreadPoolTaskScheduler maintenanceScheduler() { return scheduler("task-maintenance-", 2); }

    private static ThreadPoolTaskScheduler scheduler(String prefix, int size) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(size);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
