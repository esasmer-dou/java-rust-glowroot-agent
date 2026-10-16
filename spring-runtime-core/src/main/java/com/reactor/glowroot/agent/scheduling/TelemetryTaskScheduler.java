package com.reactor.glowroot.agent.scheduling;

import com.reactor.glowroot.agent.runtime.NativeOperation;
import com.reactor.glowroot.agent.runtime.OperationTelemetrySink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.SchedulingAwareRunnable;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/** Decorates registrations, not each tick. The application's scheduler and pool remain unchanged. */
public final class TelemetryTaskScheduler implements TaskScheduler {
    private final TaskScheduler delegate;
    private final OperationTelemetrySink sink;

    public TelemetryTaskScheduler(TaskScheduler delegate, OperationTelemetrySink sink) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.sink = sink;
    }

    private Runnable observed(Runnable task) {
        // Spring already owns the method invocation; we do not scan or invoke methods reflectively.
        String name = task instanceof ScheduledMethodRunnable ? task.toString() : task.getClass().getName();
        return new ObservedTask(task, NativeOperation.register(sink, "Scheduler", name).wrap(task));
    }

    private record ObservedTask(Runnable task, Runnable observed) implements SchedulingAwareRunnable {
        @Override public void run() { observed.run(); }
        @Override public boolean isLongLived() {
            return task instanceof SchedulingAwareRunnable aware && aware.isLongLived();
        }
        @Override public String getQualifier() {
            return task instanceof SchedulingAwareRunnable aware ? aware.getQualifier() : null;
        }
    }

    @Override public Clock getClock() { return delegate.getClock(); }
    @Override public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
        return delegate.schedule(observed(task), trigger);
    }
    @Override public ScheduledFuture<?> schedule(Runnable task, Instant start) {
        return delegate.schedule(observed(task), start);
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) {
        return delegate.scheduleAtFixedRate(observed(task), start, period);
    }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
        return delegate.scheduleAtFixedRate(observed(task), period);
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
        return delegate.scheduleWithFixedDelay(observed(task), start, delay);
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
        return delegate.scheduleWithFixedDelay(observed(task), delay);
    }
}
