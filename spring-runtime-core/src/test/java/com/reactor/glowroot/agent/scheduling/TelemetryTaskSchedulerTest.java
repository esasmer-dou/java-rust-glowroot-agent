package com.reactor.glowroot.agent.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.glowroot.agent.runtime.OperationTelemetrySink;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

class TelemetryTaskSchedulerTest {
    @Test void preservesQualifiedSchedulerAndCountsEveryTickWithoutRegistrationPerTick() throws Exception {
        var selected = new ThreadPoolTaskScheduler();
        selected.setThreadNamePrefix("selected-scheduler-");
        selected.initialize();
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("selected", selected);
        var router = new TaskSchedulerRouter();
        router.setBeanFactory(beans);
        var calls = new AtomicInteger();
        var registered = new AtomicInteger();
        var latch = new CountDownLatch(3);
        OperationTelemetrySink sink = new OperationTelemetrySink() {
            public int registerOperation(String type, String name) {
                assertEquals("Scheduler", type);
                registered.incrementAndGet();
                return 1;
            }
            public void recordOperation(int slot, long duration, Throwable error) {
                calls.incrementAndGet(); latch.countDown();
            }
        };
        try {
            var task = new ScheduledMethodRunnable(new Job(), Job.class.getMethod("tick"),
                    "selected", () -> io.micrometer.observation.ObservationRegistry.NOOP);
            var scheduler = new TelemetryTaskScheduler(router, sink);
            var future = scheduler.scheduleAtFixedRate(task, Duration.ofMillis(10));
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            future.cancel(false);
            assertEquals(1, registered.get());
            assertTrue(calls.get() >= 3);
        } finally { router.destroy(); selected.destroy(); }
    }

    public static final class Job {
        public void tick() { assertTrue(Thread.currentThread().getName().startsWith("selected-scheduler-")); }
    }
}
