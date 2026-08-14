import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/** OpenJ9-friendly smoke and resident-memory probe for repeated runtime profile transitions. */
public final class ProfileSwitchProbe {

    private ProfileSwitchProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("native library path and optional profile are required");
        }
        TelemetryProfile targetProfile = args.length == 2
                ? TelemetryProfile.parse(args[1])
                : TelemetryProfile.FULL;
        System.setProperty("reactor.glowroot.native.path", Path.of(args[0]).toAbsolutePath().toString());
        String collectorAddress = System.getenv().getOrDefault(
                "GLOWROOT_PROBE_COLLECTOR",
                "http://127.0.0.1:1"
        );
        long firstProfileHoldMs = Long.parseLong(
                System.getenv().getOrDefault("GLOWROOT_PROBE_HOLD_MS", "0")
        );
        int requestTimeoutMs = Integer.parseInt(
                System.getenv().getOrDefault("GLOWROOT_PROBE_REQUEST_TIMEOUT_MS", "100")
        );
        TelemetryConfig config = new TelemetryConfig(
                collectorAddress,
                "profile-switch::probe",
                "profile-switch-probe",
                "localhost",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                "profile-switch-probe",
                ProcessHandle.current().pid(),
                System.currentTimeMillis(),
                60_000,
                100,
                requestTimeoutMs,
                500,
                256,
                0,
                8,
                16 * 1024
        );

        try (NativeTelemetry telemetry = NativeTelemetry.start(config)) {
            int routeSlot = telemetry.registerHttpRoute("GET", "/profile-switch/{id}");
            NativeTelemetry.SqlStatement statement = telemetry.sqlStatement(
                    "customer.find",
                    "select id from customer where id = ?"
            );
            long[] upgradeNanos = new long[100];
            long[] downgradeNanos = new long[100];
            report("micro-start", telemetry);
            for (int iteration = 0; iteration < 100; iteration++) {
                long transitionStarted = System.nanoTime();
                telemetry.updateProfile(targetProfile, Duration.ofSeconds(2));
                upgradeNanos[iteration] = System.nanoTime() - transitionStarted;
                if (iteration == 0 && requiresJvmProbe(targetProfile)) {
                    String activeDiagnostics = telemetry.diagnosticsJson();
                    require(activeDiagnostics.contains("\"jvm_probe_registered\":true"), activeDiagnostics);
                    require(!activeDiagnostics.contains("\"jvm_probe_owned_global_refs\":0"), activeDiagnostics);
                    if (firstProfileHoldMs > 0L) Thread.sleep(firstProfileHoldMs);
                }
                if (targetProfile == TelemetryProfile.SQL
                        || targetProfile == TelemetryProfile.FULL
                        || targetProfile == TelemetryProfile.DIAGNOSTIC) {
                    int slot = statement.activeSlot();
                    telemetry.recordSql(slot, 100_000, false, 1);
                    telemetry.recordError(
                            routeSlot,
                            200_000,
                            new IllegalStateException("profile-switch-" + iteration)
                    );
                }
                if (iteration == 0) {
                    telemetry.recordError(routeSlot, 1L, new HostileThrowable());
                    if (targetProfile == TelemetryProfile.DIAGNOSTIC) {
                        Path dump = Files.createTempDirectory("rust-glowroot-diagnostic-")
                                .resolve("threads.txt");
                        long diagnosticId = telemetry.submitDiagnostic("thread-dump", dump);
                        awaitDiagnostic(telemetry, diagnosticId, dump);
                        Files.deleteIfExists(dump);
                        Files.deleteIfExists(dump.getParent());
                    }
                    try (NativeTelemetry sharedBaseline = NativeTelemetry.start(config)) {
                        require(
                                sharedBaseline.configuredProfile() == TelemetryProfile.MICRO,
                                sharedBaseline.diagnosticsJson()
                        );
                        require(
                                sharedBaseline.activeProfile() == targetProfile,
                                sharedBaseline.diagnosticsJson()
                        );
                    }
                    try {
                        NativeTelemetry unexpected = NativeTelemetry.start(
                                withProfile(config, TelemetryProfile.FULL)
                        );
                        unexpected.close();
                        throw new IllegalStateException("different concurrent baseline was accepted");
                    } catch (IllegalStateException expected) {
                        require(
                                expected.getMessage().contains("same startup baseline"),
                                expected.getMessage()
                        );
                    }
                }
                if (iteration == 0) report(targetProfile.name().toLowerCase() + "-first", telemetry);
                if (iteration == 99) report(targetProfile.name().toLowerCase() + "-last", telemetry);
                transitionStarted = System.nanoTime();
                telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
                downgradeNanos[iteration] = System.nanoTime() - transitionStarted;
                if (iteration == 0) {
                    System.gc();
                    Thread.sleep(250);
                    report("micro-after-first-cycle", telemetry);
                }
            }
            System.gc();
            Thread.sleep(1_000);
            report("micro-after-100-cycles", telemetry);
            String diagnostics = telemetry.diagnosticsJson();
            require(diagnostics.contains("\"active_profile\":\"micro\""), diagnostics);
            require(diagnostics.contains("\"active_profile_memory_ceiling_bytes\":0"), diagnostics);
            require(diagnostics.contains("\"retired_profile_memory_ceiling_bytes\":0"), diagnostics);
            require(diagnostics.contains("\"profile_release_pending\":false"), diagnostics);
            require(diagnostics.contains("\"jvm_probe_registered\":false"), diagnostics);
            require(diagnostics.contains("\"jvm_probe_owned_global_refs\":0"), diagnostics);
            require(diagnostics.contains("\"profile_last_release_micros\":"), diagnostics);
            require(diagnostics.contains("\"profile_max_release_micros\":"), diagnostics);
            transitionReport("upgrade", upgradeNanos);
            transitionReport("downgrade-release", downgradeNanos);
        }

        TelemetryConfig fullBaseline = withProfile(config, TelemetryProfile.FULL);
        try (NativeTelemetry telemetry = NativeTelemetry.start(fullBaseline)) {
            require(telemetry.configuredProfile() == TelemetryProfile.FULL, telemetry.diagnosticsJson());
            telemetry.updateProfile(TelemetryProfile.MICRO, Duration.ofSeconds(2));
            telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            require(telemetry.activeProfile() == TelemetryProfile.FULL, telemetry.diagnosticsJson());
        }
        try (NativeTelemetry telemetry = NativeTelemetry.start(config)) {
            require(telemetry.activeProfile() == TelemetryProfile.MICRO, telemetry.diagnosticsJson());
            require(
                    telemetry.diagnosticsJson().contains("\"profile_release_pending\":false"),
                    telemetry.diagnosticsJson()
            );
        }
    }

    private static void transitionReport(String operation, long[] values) {
        Arrays.sort(values);
        System.out.printf(
                "transition=%s p50_us=%d p95_us=%d p99_us=%d max_us=%d%n",
                operation,
                micros(values[49]),
                micros(values[94]),
                micros(values[98]),
                micros(values[99])
        );
    }

    private static void awaitDiagnostic(
            NativeTelemetry telemetry,
            long diagnosticId,
            Path output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String diagnostics = telemetry.diagnosticsJson();
            if (diagnostics.contains("\"last_diagnostic_id\":" + diagnosticId)) {
                require(Files.isRegularFile(output) && Files.size(output) > 0L, diagnostics);
                return;
            }
            Thread.sleep(10L);
        }
        throw new IllegalStateException("native diagnostic did not complete: " + telemetry.diagnosticsJson());
    }

    private static long micros(long nanos) {
        return Math.max(0L, nanos / 1_000L);
    }

    private static boolean requiresJvmProbe(TelemetryProfile profile) {
        return profile == TelemetryProfile.JVM
                || profile == TelemetryProfile.FULL
                || profile == TelemetryProfile.DIAGNOSTIC;
    }

    private static void report(String phase, NativeTelemetry telemetry) throws Exception {
        long rssKb = Files.readAllLines(Path.of("/proc/self/status")).stream()
                .filter(line -> line.startsWith("VmRSS:"))
                .map(line -> line.replaceAll("[^0-9]", ""))
                .filter(value -> !value.isEmpty())
                .mapToLong(Long::parseLong)
                .findFirst()
                .orElse(-1L);
        var threads = Thread.getAllStackTraces().keySet();
        String threadNames = threads.stream()
                .map(Thread::getName)
                .sorted()
                .collect(Collectors.joining(","));
        System.out.printf(
                "phase=%s rss_kb=%d threads=%d profile=%s thread_names=%s diagnostics=%s%n",
                phase,
                rssKb,
                threads.size(),
                telemetry.activeProfile(),
                threadNames,
                telemetry.diagnosticsJson()
        );
    }

    private static void require(boolean condition, String diagnostics) {
        if (!condition) throw new IllegalStateException("profile release gate failed: " + diagnostics);
    }

    private static TelemetryConfig withProfile(
            TelemetryConfig config,
            TelemetryProfile profile) {
        return new TelemetryConfig(
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
                profile,
                config.profileReleaseTimeoutMs(),
                config.sqlCapacity(),
                config.errorTraceCapacity(),
                config.errorMaxFrames(),
                config.errorMaxBytes()
        );
    }

    private static final class HostileThrowable extends RuntimeException {
        @Override
        public StackTraceElement[] getStackTrace() {
            throw new IllegalStateException("telemetry must clear this JNI exception");
        }
    }
}
