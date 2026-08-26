package com.reactor.glowroot.agent.http;

/** Minimal Java-to-Rust HTTP telemetry boundary. */
public interface HttpTelemetrySink {

    int registerHttpRoute(String method, String route);

    /** Records an exact aggregate and optionally marks the request as a bounded trace sample. */
    void recordHttp(int slot, int status, long durationNanos, int sampleWeight);

    /** Records one trace-selected completion with bounded HTTP and controller metadata. */
    default void recordHttpDetail(
            int slot,
            int status,
            long durationNanos,
            int sampleWeight,
            String method,
            String controller,
            Throwable error) {
        recordHttpDetail(
                slot,
                status,
                durationNanos,
                sampleWeight,
                method,
                controller,
                -1L,
                -1L,
                -1L,
                -1L,
                error);
    }

    /** Records one selected trace with bounded request-thread deltas. */
    default void recordHttpDetail(
            int slot,
            int status,
            long durationNanos,
            int sampleWeight,
            String method,
            String controller,
            long cpuNanos,
            long blockedNanos,
            long waitedNanos,
            long allocatedBytes,
            Throwable error) {
        recordHttp(slot, status, durationNanos, sampleWeight);
        if (error != null) recordError(slot, durationNanos, error);
    }

    /** Records selected Spring and Dubbo request details without retaining application objects. */
    default void recordHttpDetail(
            int slot,
            int status,
            long durationNanos,
            int sampleWeight,
            String method,
            String controller,
            long cpuNanos,
            long blockedNanos,
            long waitedNanos,
            long allocatedBytes,
            long controllerStartOffsetNanos,
            long controllerDurationNanos,
            String dubboOperation,
            long dubboStartOffsetNanos,
            long dubboDurationNanos,
            long dubboCount,
            long dubboErrors,
            Throwable error) {
        recordHttpDetail(
                slot,
                status,
                durationNanos,
                sampleWeight,
                method,
                controller,
                cpuNanos,
                blockedNanos,
                waitedNanos,
                allocatedBytes,
                error);
    }

    default void recordError(int slot, long durationNanos, Throwable error) {}
}
