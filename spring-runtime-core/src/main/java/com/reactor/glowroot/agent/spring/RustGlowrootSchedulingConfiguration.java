package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.scheduling.TelemetryTaskScheduler;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TaskSchedulerRouter;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(name = "org.springframework.context.annotation.internalScheduledAnnotationProcessor")
@ConditionalOnProperty(prefix = "reactor.glowroot.scheduler", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RustGlowrootSchedulingConfiguration implements SchedulingConfigurer, Ordered, DisposableBean {
    private final NativeTelemetry telemetry;
    private final ListableBeanFactory beans;
    private TaskSchedulerRouter ownedRouter;

    public RustGlowrootSchedulingConfiguration(NativeTelemetry telemetry, ListableBeanFactory beans) {
        this.telemetry = telemetry;
        this.beans = beans;
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    @Override public void configureTasks(ScheduledTaskRegistrar registrar) {
        TaskScheduler scheduler = registrar.getScheduler();
        if (scheduler instanceof TelemetryTaskScheduler) return;
        if (scheduler == null) {
            // Preserve Spring's named scheduler, ScheduledExecutorService and default routing rules.
            ownedRouter = new TaskSchedulerRouter();
            ownedRouter.setBeanFactory(beans);
            scheduler = ownedRouter;
        }
        registrar.setTaskScheduler(new TelemetryTaskScheduler(scheduler, telemetry));
    }

    @Override public void destroy() {
        if (ownedRouter != null) ownedRouter.destroy();
    }
}
