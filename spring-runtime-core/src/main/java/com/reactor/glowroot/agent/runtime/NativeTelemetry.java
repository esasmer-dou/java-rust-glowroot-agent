package com.reactor.glowroot.agent.runtime;

import com.reactor.glowroot.agent.http.HttpTelemetrySink;

import java.nio.file.Path;
import java.time.Duration;

/** Process-scoped facade for the bounded native telemetry runtime. */
public final class NativeTelemetry implements AutoCloseable, HttpTelemetrySink {

    static final int EXPECTED_GLOWROOT_ABI = 4;
    private static final Object LIFECYCLE_LOCK = new Object();
    private static int references;
    private static volatile TelemetryProfile activeProfile = TelemetryProfile.MICRO;
    private static volatile TelemetryProfile processConfiguredProfile = TelemetryProfile.MICRO;
    private static volatile long profileGeneration = 1L;
    private static long pendingProfileReleaseTransition;

    private final int maxRoutes;
    private final int profileReleaseTimeoutMs;
    private final TelemetryProfile configuredProfile;
    private volatile boolean closed;

    private NativeTelemetry(
            int maxRoutes,
            int profileReleaseTimeoutMs,
            TelemetryProfile configuredProfile) {
        this.maxRoutes = maxRoutes;
        this.profileReleaseTimeoutMs = profileReleaseTimeoutMs;
        this.configuredProfile = configuredProfile;
    }

    /**
     * Loads, configures, and starts the standalone Rust exporter.
     *
     * @param config validated bounded telemetry configuration
     * @return process-scoped telemetry handle
     */
    public static NativeTelemetry start(TelemetryConfig config) {
        java.util.Objects.requireNonNull(config, "config");
        synchronized (LIFECYCLE_LOCK) {
            NativeLibraryLoader.load();
            int abi = nativeGlowrootAbiVersion();
            if (abi != EXPECTED_GLOWROOT_ABI) {
                throw new IllegalStateException(
                        "Glowroot native ABI mismatch: Java expects " + EXPECTED_GLOWROOT_ABI
                                + " but the loaded binary exposes " + abi
                );
            }
            if (references > 0 && config.profile() != processConfiguredProfile) {
                throw new IllegalStateException(
                        "Glowroot telemetry is process-scoped and already uses configured profile "
                                + processConfiguredProfile.propertyValue()
                                + "; every concurrent handle must use the same startup baseline"
                );
            }
            boolean firstReference = references == 0;
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
                        config.maxExportBytes(),
                        config.profile().featureMask(),
                        config.sqlCapacity(),
                        config.errorTraceCapacity(),
                        config.errorMaxFrames(),
                        config.errorMaxBytes()
            );
            nativeStart();
            if (firstReference) {
                awaitPendingProfileRelease(config.profileReleaseTimeoutMs());
                long transitionId = nativeUpdateProfile(config.profile().featureMask());
                activeProfile = config.profile();
                processConfiguredProfile = config.profile();
                profileGeneration++;
                awaitProfileRelease(transitionId, config.profileReleaseTimeoutMs());
                System.err.println(
                        "Java-Rust Glowroot native telemetry active: abi=" + abi
                                + ", profile=" + config.profile().propertyValue()
                                + ", application=" + config.applicationName()
                );
            }
            references++;
            return new NativeTelemetry(
                    config.maxRoutes(),
                    config.profileReleaseTimeoutMs(),
                    processConfiguredProfile
            );
        }
    }

    /**
     * Returns the configured native route-table bound.
     *
     * @return configured maximum route count
     */
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
     * Records one completed HTTP request in exact native aggregate state.
     *
     * @param slot previously registered route slot
     * @param status HTTP response status
     * @param durationNanos request duration in nanoseconds
     * @param sampleWeight optional trace sampling weight; aggregate count and duration stay exact
     */
    public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
        if (!closed) {
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

    /** Switches telemetry surfaces without restarting the JVM. */
    public void updateProfile(TelemetryProfile profile) {
        updateProfile(profile, Duration.ofMillis(profileReleaseTimeoutMs));
    }

    /** Switches profile and waits until all retired native profile state is released. */
    public void updateProfile(TelemetryProfile profile, Duration releaseTimeout) {
        TelemetryProfile required = java.util.Objects.requireNonNull(profile, "profile");
        java.util.Objects.requireNonNull(releaseTimeout, "releaseTimeout");
        long timeoutMs = releaseTimeout.toMillis();
        if (timeoutMs < 100 || timeoutMs > 60_000) {
            throw new IllegalArgumentException("releaseTimeout must be between 100 ms and 60 s");
        }
        synchronized (LIFECYCLE_LOCK) {
            requireOpen();
            awaitPendingProfileRelease(Math.toIntExact(timeoutMs));
            if (required == activeProfile) return;
            long transitionId = nativeUpdateProfile(required.featureMask());
            activeProfile = required;
            profileGeneration++;
            awaitProfileRelease(transitionId, Math.toIntExact(timeoutMs));
        }
    }

    public TelemetryProfile activeProfile() {
        requireOpen();
        return activeProfile;
    }

    /** Returns the profile selected when this process-scoped handle was created. */
    public TelemetryProfile configuredProfile() {
        requireOpen();
        return configuredProfile;
    }

    /** Returns to the configured startup profile and waits for retired state to be released. */
    public void restoreConfiguredProfile() {
        updateProfile(configuredProfile);
    }

    /** Returns to the configured startup profile with an explicit control-plane timeout. */
    public void restoreConfiguredProfile(Duration releaseTimeout) {
        updateProfile(configuredProfile, releaseTimeout);
    }

    public int registerSql(String operation, String sql) {
        requireOpen();
        if (!sqlEnabled()) return -1;
        return nativeRegisterSql(operation, sql);
    }

    public SqlStatement sqlStatement(String operation, String sql) {
        requireOpen();
        return new SqlStatement(this, operation, sql);
    }

    public void recordSql(int slot, long durationNanos, boolean error, long rows) {
        if (!closed && slot >= 0 && sqlEnabled()) {
            nativeRecordSql(slot, Math.max(0L, durationNanos), error, Math.max(0L, rows));
        }
    }

    public void recordError(int slot, long durationNanos, Throwable error) {
        if (!closed && slot >= 0 && error != null && errorStacksEnabled()) {
            nativeRecordError(slot, Math.max(0L, durationNanos), error);
        }
    }

    public long submitDiagnostic(String operation, Path outputPath) {
        requireOpen();
        if (!diagnosticsEnabled()) {
            throw new IllegalStateException("Glowroot diagnostic profile is not active");
        }
        int kind = switch (operation == null ? "" : operation.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "thread-dump" -> 1;
            case "heap-dump" -> 2;
            case "heap-histogram" -> 3;
            default -> throw new IllegalArgumentException(
                    "Diagnostic operation must be thread-dump, heap-dump, or heap-histogram"
            );
        };
        return nativeRequestDiagnostic(
                kind,
                java.util.Objects.requireNonNull(outputPath, "outputPath").toString()
        );
    }

    private boolean sqlEnabled() {
        return (activeProfile.featureMask() & (1 << 1)) != 0;
    }

    private boolean errorStacksEnabled() {
        return (activeProfile.featureMask() & (1 << 2)) != 0;
    }

    private boolean diagnosticsEnabled() {
        return (activeProfile.featureMask() & (1 << 3)) != 0;
    }

    /** Startup-owned statement descriptor that safely re-registers after a profile transition. */
    public static final class SqlStatement {
        private final NativeTelemetry telemetry;
        private final String operation;
        private final String sql;
        private volatile long generation = Long.MIN_VALUE;
        private volatile int slot = -1;

        private SqlStatement(NativeTelemetry telemetry, String operation, String sql) {
            this.telemetry = telemetry;
            this.operation = java.util.Objects.requireNonNull(operation, "operation");
            this.sql = java.util.Objects.requireNonNull(sql, "sql");
        }

        public int activeSlot() {
            if (telemetry.closed || !telemetry.sqlEnabled()) return -1;
            long currentGeneration = NativeTelemetry.profileGeneration;
            if (generation == currentGeneration) return slot;
            synchronized (this) {
                if (generation != currentGeneration) {
                    slot = telemetry.registerSql(operation, sql);
                    generation = currentGeneration;
                }
                return slot;
            }
        }

        /** Starts one SQL timing without allocating an observation object. */
        public long start() {
            return System.nanoTime();
        }

        public void recordSuccess(long startedAtNanos, long rows) {
            int activeSlot = activeSlot();
            telemetry.recordSql(activeSlot, elapsedSince(startedAtNanos), false, rows);
        }

        public void recordFailure(long startedAtNanos, Throwable error) {
            int activeSlot = activeSlot();
            long durationNanos = elapsedSince(startedAtNanos);
            telemetry.recordSql(activeSlot, durationNanos, true, 0L);
            telemetry.recordError(activeSlot, durationNanos, error);
        }

        private static long elapsedSince(long startedAtNanos) {
            return Math.max(0L, System.nanoTime() - startedAtNanos);
        }
    }

    @Override
    public void close() {
        synchronized (LIFECYCLE_LOCK) {
            if (closed) return;
            if (references > 1) {
                references--;
                closed = true;
                return;
            }
            if (references <= 0) {
                closed = true;
                return;
            }

            awaitPendingProfileRelease(profileReleaseTimeoutMs);
            if (activeProfile != TelemetryProfile.MICRO) {
                long transitionId = nativeUpdateProfile(TelemetryProfile.MICRO.featureMask());
                activeProfile = TelemetryProfile.MICRO;
                profileGeneration++;
                awaitProfileRelease(transitionId, profileReleaseTimeoutMs);
            }
            nativeStop();
            references = 0;
            closed = true;
        }
    }

    private static void awaitProfileRelease(long transitionId, int timeoutMs) {
        pendingProfileReleaseTransition = transitionId;
        awaitPendingProfileRelease(timeoutMs);
    }

    private static void awaitPendingProfileRelease(int timeoutMs) {
        long transitionId = pendingProfileReleaseTransition;
        if (transitionId == 0L) return;
        if (!nativeAwaitProfileRelease(transitionId, timeoutMs)) {
            throw new IllegalStateException(
                    "Glowroot profile changed, but retired native state was not released within "
                            + timeoutMs + " ms; inspect diagnosticsJson() before retrying"
            );
        }
        pendingProfileReleaseTransition = 0L;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Native telemetry is already closed");
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
            int maxExportBytes,
            int featureMask,
            int sqlCapacity,
            int errorTraceCapacity,
            int errorMaxFrames,
            int errorMaxBytes);

    private static native void nativeStart();

    private static native void nativeStop();

    private static native int nativeRegisterHttpRoute(String method, String route);

    private static native void nativeRecordHttp(
            int slot,
            int status,
            long durationNanos,
            int sampleWeight);

    private static native long nativeUpdateProfile(int featureMask);

    private static native boolean nativeAwaitProfileRelease(long transitionId, int timeoutMs);

    private static native int nativeRegisterSql(String operation, String sql);

    private static native void nativeRecordSql(
            int slot,
            long durationNanos,
            boolean error,
            long rows);

    private static native boolean nativeRecordError(
            int slot,
            long durationNanos,
            Throwable error);

    private static native long nativeRequestDiagnostic(int kind, String path);

    private static native String nativeDiagnosticsJson();

}
