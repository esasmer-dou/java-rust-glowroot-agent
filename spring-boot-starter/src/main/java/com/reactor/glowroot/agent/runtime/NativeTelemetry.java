package com.reactor.glowroot.agent.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-scoped facade for the bounded native telemetry runtime. */
public final class NativeTelemetry implements AutoCloseable {

    static final int EXPECTED_GLOWROOT_ABI = 1;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static int references;

    private final int maxRoutes;
    private final AtomicBoolean closed = new AtomicBoolean();

    private NativeTelemetry(int maxRoutes) {
        this.maxRoutes = maxRoutes;
    }

    /**
     * Loads, configures, and starts the standalone Rust exporter.
     *
     * @param config validated bounded telemetry configuration
     * @return process-scoped telemetry handle
     */
    public static NativeTelemetry start(TelemetryConfig config) {
        synchronized (LIFECYCLE_LOCK) {
            NativeLibraryLoader.load();
            int abi = nativeGlowrootAbiVersion();
            if (abi != EXPECTED_GLOWROOT_ABI) {
                throw new IllegalStateException(
                        "Glowroot native ABI mismatch: Java expects " + EXPECTED_GLOWROOT_ABI
                                + " but the loaded binary exposes " + abi
                );
            }
            nativeConfigure(
                    config.collectorAddress(),
                    config.agentId(),
                    config.applicationName(),
                    config.hostname(),
                    config.javaVersion(),
                    config.javaVm(),
                    config.agentVersion(),
                    config.processId(),
                    config.processStartTimeMs(),
                    config.exportIntervalMs(),
                    config.connectTimeoutMs(),
                    config.requestTimeoutMs(),
                    config.slowThresholdMs(),
                    config.httpSampleRate(),
                    config.traceCapacity(),
                    config.maxRoutes(),
                    config.maxExportBytes()
            );
            nativeStart();
            references++;
            return new NativeTelemetry(config.maxRoutes());
        }
    }

    /** @return configured maximum route count */
    public int maxRoutes() {
        return maxRoutes;
    }

    /**
     * Registers a bounded HTTP route slot.
     *
     * @param method HTTP method
     * @param route normalized route pattern
     * @return native slot, or {@code -1} when the route bound is full
     */
    public int registerHttpRoute(String method, String route) {
        requireOpen();
        return nativeRegisterHttpRoute(method, route);
    }

    /**
     * Records one sampled or failed HTTP request in native aggregate state.
     *
     * @param slot previously registered route slot
     * @param status HTTP response status
     * @param durationNanos request duration in nanoseconds
     * @param sampleWeight successful request weight, or zero for an exact error
     */
    public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
        if (!closed.get()) {
            nativeRecordHttp(slot, status, durationNanos, sampleWeight);
        }
    }

    /**
     * Returns bounded exporter state without enabling JMX or a management server.
     *
     * @return diagnostics JSON
     */
    public String diagnosticsJson() {
        requireOpen();
        return nativeDiagnosticsJson();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (LIFECYCLE_LOCK) {
            references--;
            if (references <= 0) {
                references = 0;
                nativeStop();
            }
        }
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Native telemetry is already closed");
    }

    static native int nativeGlowrootAbiVersion();

    private static native void nativeConfigure(
            String collectorAddress,
            String agentId,
            String applicationName,
            String hostname,
            String javaVersion,
            String javaVm,
            String agentVersion,
            long processId,
            long processStartTimeMs,
            int exportIntervalMs,
            int connectTimeoutMs,
            int requestTimeoutMs,
            int slowThresholdMs,
            int httpSampleRate,
            int traceCapacity,
            int maxRoutes,
            int maxExportBytes);

    private static native void nativeStart();

    private static native void nativeStop();

    private static native int nativeRegisterHttpRoute(String method, String route);

    private static native void nativeRecordHttp(
            int slot,
            int status,
            long durationNanos,
            int sampleWeight);

    private static native String nativeDiagnosticsJson();
}
