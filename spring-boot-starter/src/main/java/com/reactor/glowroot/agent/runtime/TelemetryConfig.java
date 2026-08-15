package com.reactor.glowroot.agent.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable, bounded configuration shared by Spring Boot adapters and the native exporter.
 *
 * @param collectorAddress Glowroot Central gRPC over HTTP/2 endpoint
 * @param agentId unique agent or rollup identity
 * @param applicationName application name shown by Glowroot
 * @param hostname host or pod identity
 * @param javaVersion running Java version
 * @param javaVm running JVM name
 * @param agentVersion agent artifact version
 * @param processId operating-system process id
 * @param processStartTimeMs process start time in epoch milliseconds
 * @param exportIntervalMs bounded aggregate export interval
 * @param connectTimeoutMs collector connection timeout
 * @param requestTimeoutMs complete collector request timeout
 * @param slowThresholdMs threshold used when bounded traces are enabled
 * @param httpSampleRate successful HTTP request sampling rate
 * @param traceCapacity bounded trace queue capacity
 * @param maxRoutes maximum number of retained HTTP route slots
 * @param maxExportBytes maximum encoded collector request size
 * @param profile bounded telemetry surfaces enabled at startup
 * @param profileReleaseTimeoutMs maximum synchronous wait for retired profile state
 * @param sqlCapacity maximum SQL statement slots in SQL-capable profiles
 * @param errorTraceCapacity maximum retained detailed error traces
 * @param errorMaxFrames maximum stack frames copied for one detailed error
 * @param errorMaxBytes maximum UTF-8 detail bytes copied for one detailed error
 */
public record TelemetryConfig(
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
        TelemetryProfile profile,
        int profileReleaseTimeoutMs,
        int sqlCapacity,
        int errorTraceCapacity,
        int errorMaxFrames,
        int errorMaxBytes) {

    private static final String PREFIX = "reactor.glowroot.";
    private static final long PROCESS_START_FALLBACK_MS = System.currentTimeMillis();

    /** Validates and normalizes every externally supplied value. */
    public TelemetryConfig {
        collectorAddress = required(collectorAddress, PREFIX + "collector.address");
        agentId = required(agentId, PREFIX + "agent.id");
        applicationName = required(applicationName, PREFIX + "application.name");
        hostname = required(hostname, PREFIX + "hostname");
        javaVersion = required(javaVersion, "java.version");
        javaVm = required(javaVm, "java.vm.name");
        agentVersion = required(agentVersion, PREFIX + "agent.version");
        exportIntervalMs = bounded(exportIntervalMs, 60_000, 3_600_000, "export interval");
        if (exportIntervalMs % 60_000 != 0) {
            throw new IllegalArgumentException("Glowroot export interval must be a multiple of 60000 ms");
        }
        connectTimeoutMs = bounded(connectTimeoutMs, 100, 30_000, "connect timeout");
        requestTimeoutMs = bounded(requestTimeoutMs, 100, 30_000, "request timeout");
        slowThresholdMs = bounded(slowThresholdMs, 1, 3_600_000, "slow threshold");
        httpSampleRate = bounded(httpSampleRate, 1, 1024, "HTTP sample rate");
        if ((httpSampleRate & (httpSampleRate - 1)) != 0) {
            throw new IllegalArgumentException("Glowroot HTTP sample rate must be a power of two");
        }
        traceCapacity = bounded(traceCapacity, 0, 32, "trace capacity");
        maxRoutes = bounded(maxRoutes, 1, 64, "max routes");
        maxExportBytes = bounded(maxExportBytes, 16 * 1024, 64 * 1024, "max export bytes");
        profile = Objects.requireNonNull(profile, "profile");
        profileReleaseTimeoutMs = bounded(
                profileReleaseTimeoutMs,
                100,
                60_000,
                "profile release timeout"
        );
        sqlCapacity = bounded(sqlCapacity, 0, 32, "SQL capacity");
        errorTraceCapacity = bounded(errorTraceCapacity, 0, 16, "error trace capacity");
        errorMaxFrames = bounded(errorMaxFrames, 0, 32, "error max frames");
        errorMaxBytes = bounded(errorMaxBytes, 256, 8 * 1024, "error max bytes");
    }

    /** Source-compatible constructor for the original bounded micro profile. */
    public TelemetryConfig(
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
            int maxExportBytes) {
        this(
                collectorAddress,
                agentId,
                applicationName,
                hostname,
                javaVersion,
                javaVm,
                agentVersion,
                processId,
                processStartTimeMs,
                exportIntervalMs,
                connectTimeoutMs,
                requestTimeoutMs,
                slowThresholdMs,
                httpSampleRate,
                traceCapacity,
                maxRoutes,
                maxExportBytes,
                TelemetryProfile.MICRO,
                5_000,
                16,
                8,
                24,
                4 * 1024
        );
    }

    /**
     * Reads configuration from JVM properties and their matching environment variables.
     *
     * @return validated process configuration
     */
    public static TelemetryConfig fromSystemProperties() {
        return from(key -> {
            String value = System.getProperty(key);
            return value == null ? System.getenv(environmentKey(key)) : value;
        });
    }

    /**
     * Builds a configuration from a framework-specific property resolver.
     *
     * @param resolver property resolver; return {@code null} for an absent key
     * @return validated process configuration
     */
    public static TelemetryConfig from(Function<String, String> resolver) {
        Objects.requireNonNull(resolver, "resolver");
        String applicationName = value(
                resolver,
                PREFIX + "application.name",
                value(resolver, "reactor.application.name", "reactor-application")
        );
        String hostname = value(resolver, PREFIX + "hostname", System.getenv("HOSTNAME"));
        if (hostname == null || hostname.isBlank()) {
            hostname = "unknown-host";
        }
        return new TelemetryConfig(
                value(resolver, PREFIX + "collector.address", "http://127.0.0.1:8181"),
                value(resolver, PREFIX + "agent.id", ""),
                applicationName,
                hostname,
                System.getProperty("java.version", "unknown"),
                System.getProperty("java.vm.name", "unknown"),
                value(resolver, PREFIX + "agent.version", implementationVersion()),
                ProcessHandle.current().pid(),
                longValue(resolver, PREFIX + "process-start-time-ms", PROCESS_START_FALLBACK_MS),
                intValue(resolver, PREFIX + "export.interval-ms", 60_000),
                intValue(resolver, PREFIX + "connect-timeout-ms", 1_000),
                intValue(resolver, PREFIX + "request-timeout-ms", 2_000),
                intValue(resolver, PREFIX + "trace.slow-threshold-ms", 500),
                intValue(resolver, PREFIX + "http.sample-rate", 256),
                intValue(resolver, PREFIX + "trace.capacity", 0),
                intValue(resolver, PREFIX + "max-routes", 64),
                intValue(resolver, PREFIX + "max-export-bytes", 65_536),
                TelemetryProfile.parse(value(resolver, PREFIX + "profile", "micro")),
                intValue(resolver, PREFIX + "profile.release-timeout-ms", 5_000),
                intValue(resolver, PREFIX + "sql.capacity", 16),
                intValue(resolver, PREFIX + "error.trace.capacity", 8),
                intValue(resolver, PREFIX + "error.max-frames", 24),
                intValue(resolver, PREFIX + "error.max-bytes", 4 * 1024)
        );
    }

    private static String implementationVersion() {
        String version = TelemetryConfig.class.getPackage().getImplementationVersion();
        return "java-rust-glowroot-agent/" + (version == null || version.isBlank() ? "dev" : version);
    }

    private static String value(Function<String, String> resolver, String key, String fallback) {
        String value = resolver.apply(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intValue(Function<String, String> resolver, String key, int fallback) {
        String value = resolver.apply(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    private static long longValue(Function<String, String> resolver, String key, long fallback) {
        String value = resolver.apply(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be a long", error);
        }
    }

    private static String environmentKey(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    private static String required(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " cannot be blank");
        }
        return value.trim();
    }

    private static int bounded(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "Glowroot " + name + " must be between " + min + " and " + max
            );
        }
        return value;
    }
}
