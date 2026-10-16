package com.reactor.glowroot.agent.http;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/** Diagnostic-only, one-array request thread delta capture. */
public final class HttpThreadStats {

    private static final int THREAD_ID = 0;
    private static final int CPU_NANOS = 1;
    private static final int BLOCKED_MILLIS = 2;
    private static final int WAITED_MILLIS = 3;
    private static final int ALLOCATED_BYTES = 4;
    private static final long UNAVAILABLE = -1L;
    private static final long[] UNAVAILABLE_STATS = {
            UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE
    };

    private static volatile Counters counters;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static boolean agentEnabledCpuTime;
    private static boolean agentEnabledContention;
    private static boolean agentEnabledAllocatedMemory;

    private HttpThreadStats() {}

    /** Enables diagnostic counters only while the diagnostic profile owns them. */
    public static void setEnabled(boolean required) {
        synchronized (LIFECYCLE_LOCK) {
            Counters active = counters;
            // A micro start/stop must not initialize JVM management just to disable diagnostics.
            if (!required && active == null) return;
            try {
                if (active == null) {
                    active = diagnosticCounters();
                }
                ThreadMXBean threads = active.threads();
                com.sun.management.ThreadMXBean allocations = active.allocations();
                if (required) {
                    if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
                        threads.setThreadCpuTimeEnabled(true);
                        agentEnabledCpuTime = true;
                    }
                    if (threads.isThreadContentionMonitoringSupported()
                            && !threads.isThreadContentionMonitoringEnabled()) {
                        threads.setThreadContentionMonitoringEnabled(true);
                        agentEnabledContention = true;
                    }
                    if (allocations != null
                            && allocations.isThreadAllocatedMemorySupported()
                            && !allocations.isThreadAllocatedMemoryEnabled()) {
                        allocations.setThreadAllocatedMemoryEnabled(true);
                        agentEnabledAllocatedMemory = true;
                    }
                    return;
                }
                if (agentEnabledAllocatedMemory
                        && allocations != null
                        && allocations.isThreadAllocatedMemoryEnabled()) {
                    allocations.setThreadAllocatedMemoryEnabled(false);
                }
                if (agentEnabledContention && threads.isThreadContentionMonitoringEnabled()) {
                    threads.setThreadContentionMonitoringEnabled(false);
                }
                if (agentEnabledCpuTime && threads.isThreadCpuTimeEnabled()) {
                    threads.setThreadCpuTimeEnabled(false);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Unsupported or restricted JVM counters remain unavailable in trace detail.
            } finally {
                if (!required) {
                    agentEnabledCpuTime = false;
                    agentEnabledContention = false;
                    agentEnabledAllocatedMemory = false;
                    counters = null;
                }
            }
        }
    }

    /** Captures cumulative counters only in the diagnostic profile. */
    public static long[] begin(boolean enabled) {
        if (!enabled) return null;
        try {
            Counters active = diagnosticCounters();
            ThreadMXBean threads = active.threads();
            long threadId = Thread.currentThread().threadId();
            ThreadInfo info = threads.getThreadInfo(threadId, 0);
            return new long[] {
                    threadId,
                    threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                            ? threads.getThreadCpuTime(threadId) : UNAVAILABLE,
                    info == null ? UNAVAILABLE : info.getBlockedTime(),
                    info == null ? UNAVAILABLE : info.getWaitedTime(),
                    allocatedBytes(active.allocations(), threadId)
            };
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Converts a start snapshot into deltas, reusing the same array. */
    public static long[] finish(Object value) {
        if (!(value instanceof long[] start) || start.length < 5) return UNAVAILABLE_STATS;
        Counters active = counters;
        if (active == null) return UNAVAILABLE_STATS;
        try {
            ThreadMXBean threads = active.threads();
            long threadId = Thread.currentThread().threadId();
            if (threadId != start[THREAD_ID]) return UNAVAILABLE_STATS;
            ThreadInfo info = threads.getThreadInfo(threadId, 0);
            long cpu = threads.isThreadCpuTimeSupported() && threads.isThreadCpuTimeEnabled()
                    ? threads.getThreadCpuTime(threadId) : UNAVAILABLE;
            long blocked = info == null ? UNAVAILABLE : info.getBlockedTime();
            long waited = info == null ? UNAVAILABLE : info.getWaitedTime();
            long allocated = allocatedBytes(active.allocations(), threadId);
            start[0] = delta(cpu, start[CPU_NANOS], 1L);
            start[1] = delta(blocked, start[BLOCKED_MILLIS], 1_000_000L);
            start[2] = delta(waited, start[WAITED_MILLIS], 1_000_000L);
            start[3] = delta(allocated, start[ALLOCATED_BYTES], 1L);
            return start;
        } catch (RuntimeException | LinkageError ignored) {
            return UNAVAILABLE_STATS;
        }
    }

    private record Counters(ThreadMXBean threads, com.sun.management.ThreadMXBean allocations) {}

    private static Counters diagnosticCounters() {
        Counters active = counters;
        if (active != null) return active;
        synchronized (LIFECYCLE_LOCK) {
            active = counters;
            if (active == null) {
                ThreadMXBean threads = ManagementFactory.getThreadMXBean();
                active = new Counters(threads,
                        threads instanceof com.sun.management.ThreadMXBean bean ? bean : null);
                counters = active;
            }
            return active;
        }
    }

    private static long allocatedBytes(com.sun.management.ThreadMXBean allocations, long threadId) {
        if (allocations == null
                || !allocations.isThreadAllocatedMemorySupported()
                || !allocations.isThreadAllocatedMemoryEnabled()) {
            return UNAVAILABLE;
        }
        return allocations.getThreadAllocatedBytes(threadId);
    }

    private static long delta(long current, long started, long multiplier) {
        if (current < 0L || started < 0L || current < started) return UNAVAILABLE;
        long difference = current - started;
        if (difference > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
        return difference * multiplier;
    }
}
