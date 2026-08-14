import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

/** Measures the real JNI/native HTTP aggregate path after dynamic profile changes. */
public final class ProfileHotPathProbe {

    private static final int WARMUP_CALLS = 2_000_000;
    private static final int MEASURED_CALLS = 10_000_000;
    private static final int ROUNDS = 8;

    private ProfileHotPathProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("native library path is required");
        System.setProperty("reactor.glowroot.native.path", Path.of(args[0]).toAbsolutePath().toString());
        TelemetryConfig config = new TelemetryConfig(
                "http://127.0.0.1:1",
                "profile-hot-path::probe",
                "profile-hot-path-probe",
                "localhost",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                "profile-hot-path-probe",
                ProcessHandle.current().pid(),
                System.currentTimeMillis(),
                60_000,
                100,
                100,
                500,
                256,
                0,
                8,
                16 * 1024
        );

        try (NativeTelemetry telemetry = NativeTelemetry.start(config)) {
            int slot = telemetry.registerHttpRoute("GET", "/profile-hot-path/{id}");
            exercise(telemetry, slot, WARMUP_CALLS);
            telemetry.updateProfile(TelemetryProfile.FULL, Duration.ofSeconds(2));
            exercise(telemetry, slot, WARMUP_CALLS);
            telemetry.updateProfile(TelemetryProfile.MICRO, Duration.ofSeconds(2));

            long[] micro = new long[ROUNDS];
            long[] full = new long[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                if ((round & 1) == 0) {
                    micro[round] = measureProfile(telemetry, slot, TelemetryProfile.MICRO);
                    full[round] = measureProfile(telemetry, slot, TelemetryProfile.FULL);
                } else {
                    full[round] = measureProfile(telemetry, slot, TelemetryProfile.FULL);
                    micro[round] = measureProfile(telemetry, slot, TelemetryProfile.MICRO);
                }
            }
            telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            Arrays.sort(micro);
            Arrays.sort(full);
            double microNs = medianNanos(micro) / MEASURED_CALLS;
            double fullNs = medianNanos(full) / MEASURED_CALLS;
            System.out.printf(
                    "hot_path calls=%d rounds=%d micro_median_ns=%.2f full_median_ns=%.2f delta_percent=%.2f%n",
                    MEASURED_CALLS,
                    ROUNDS,
                    microNs,
                    fullNs,
                    percentageDelta(microNs, fullNs)
            );
            String diagnostics = telemetry.diagnosticsJson();
            if (!diagnostics.contains("\"active_profile\":\"micro\"")
                    || !diagnostics.contains("\"profile_release_pending\":false")
                    || !diagnostics.contains("\"jvm_probe_registered\":false")
                    || !diagnostics.contains("\"jvm_probe_owned_global_refs\":0")) {
                throw new IllegalStateException("profile hot-path release gate failed: " + diagnostics);
            }
        }
    }

    private static long measureProfile(
            NativeTelemetry telemetry,
            int slot,
            TelemetryProfile profile) throws InterruptedException {
        telemetry.updateProfile(profile, Duration.ofSeconds(2));
        Thread.sleep(25);
        return measure(telemetry, slot);
    }

    private static long measure(NativeTelemetry telemetry, int slot) {
        long started = System.nanoTime();
        exercise(telemetry, slot, MEASURED_CALLS);
        return System.nanoTime() - started;
    }

    private static void exercise(NativeTelemetry telemetry, int slot, int calls) {
        for (int index = 0; index < calls; index++) {
            telemetry.recordHttp(slot, 200, 100_000L + (index & 63), 1);
        }
    }

    private static double medianNanos(long[] sorted) {
        int middle = sorted.length / 2;
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    private static double percentageDelta(double baseline, double candidate) {
        return baseline == 0.0 ? 0.0 : ((candidate - baseline) / baseline) * 100.0;
    }
}
