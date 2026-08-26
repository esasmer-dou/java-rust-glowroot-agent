package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTelemetryIntegrationTest {

    @Test
    void loadsThePackagedPlatformBinaryAndRunsTheBoundedLifecycle() throws Exception {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        boolean contentionWasEnabled = threadBean.isThreadContentionMonitoringEnabled();
        com.sun.management.ThreadMXBean allocationBean =
                threadBean instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        boolean allocatedMemoryWasEnabled = allocationBean != null
                && allocationBean.isThreadAllocatedMemorySupported()
                && allocationBean.isThreadAllocatedMemoryEnabled();
        TelemetryConfig config = new TelemetryConfig(
                "http://127.0.0.1:1",
                "native-integration::test",
                "native-integration-test",
                "localhost",
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                "integration-test",
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
            assertEquals(TelemetryProfile.MICRO, telemetry.configuredProfile());
            int slot = telemetry.registerHttpRoute("GET", "/integration/{id}");
            telemetry.recordHttp(slot, 200, 100_000, 256);
            String diagnostics = telemetry.diagnosticsJson();
            assertTrue(diagnostics.contains("\"enabled\":true"));
            assertTrue(diagnostics.contains("\"registered_routes\":1"));

            telemetry.updateProfile(TelemetryProfile.FULL, Duration.ofSeconds(2));
            assertEquals(TelemetryProfile.FULL, telemetry.activeProfile());
            try (NativeTelemetry sharedBaseline = NativeTelemetry.start(config)) {
                assertEquals(TelemetryProfile.MICRO, sharedBaseline.configuredProfile());
                assertEquals(TelemetryProfile.FULL, sharedBaseline.activeProfile());
            }
            assertThrows(
                    IllegalStateException.class,
                    () -> NativeTelemetry.start(withProfile(config, TelemetryProfile.FULL))
            );
            NativeTelemetry.SqlStatement statement = telemetry.sqlStatement(
                    "customer.find",
                    "select id from customer where id = ?"
            );
            assertTrue(statement.activeSlot() >= 0);
            long successfulCall = statement.start();
            statement.recordSuccess(successfulCall, 1L);
            long failedCall = statement.start();
            DeferredTrackingException failure = new DeferredTrackingException();
            statement.recordFailure(failedCall, failure);
            assertTrue(failure.awaitCapture(), "Rust exporter did not materialize the deferred error");
            assertNotEquals(
                    Thread.currentThread().threadId(),
                    failure.captureThreadId,
                    "Throwable inspection ran on the application test thread");

            Path diagnosticDirectory = Files.createTempDirectory("rust-glowroot-live-");
            Path threadDump = diagnosticDirectory.resolve("threads.txt");
            Path heapHistogram = diagnosticDirectory.resolve("heap-histogram.txt");
            try {
                telemetry.updateProfile(TelemetryProfile.DIAGNOSTIC, Duration.ofSeconds(2));
                diagnostics = telemetry.diagnosticsJson();
                assertTrue(diagnostics.contains("\"jvm_probe_registered\":true"));
                if (threadBean.isThreadContentionMonitoringSupported()) {
                    assertTrue(threadBean.isThreadContentionMonitoringEnabled());
                }
                if (allocationBean != null && allocationBean.isThreadAllocatedMemorySupported()) {
                    assertTrue(allocationBean.isThreadAllocatedMemoryEnabled());
                }

                assertTrue(telemetry.submitDiagnostic("thread-dump", threadDump) > 0L);
                awaitDiagnostic(threadDump);
                assertTrue(Files.readString(threadDump).contains(" Id="));

                assertTrue(telemetry.submitDiagnostic("heap-histogram", heapHistogram) > 0L);
                awaitDiagnostic(heapHistogram);
                String histogram = Files.readString(heapHistogram);
                assertTrue(histogram.contains("class name"));
                assertTrue(histogram.contains("java.lang.String"));
            } finally {
                Files.deleteIfExists(threadDump);
                Files.deleteIfExists(heapHistogram);
                Files.deleteIfExists(diagnosticDirectory);
            }

            telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            assertEquals(TelemetryProfile.MICRO, telemetry.activeProfile());
            assertEquals(contentionWasEnabled, threadBean.isThreadContentionMonitoringEnabled());
            if (allocationBean != null && allocationBean.isThreadAllocatedMemorySupported()) {
                assertEquals(
                        allocatedMemoryWasEnabled,
                        allocationBean.isThreadAllocatedMemoryEnabled());
            }
            statement.recordSuccess(statement.start(), 1L);
            assertEquals(-1, statement.activeSlot());
            diagnostics = telemetry.diagnosticsJson();
            assertTrue(diagnostics.contains("\"active_profile\":\"micro\""));
            assertTrue(diagnostics.contains("\"active_profile_memory_ceiling_bytes\":0"));
            assertTrue(diagnostics.contains("\"profile_release_pending\":false"));
            assertTrue(diagnostics.contains("\"jvm_probe_registered\":false"));
            assertTrue(diagnostics.contains("\"profile_last_release_micros\":"));
            assertTrue(diagnostics.contains("\"profile_max_release_micros\":"));
        }

        TelemetryConfig restartedFull = withProfile(config, TelemetryProfile.FULL);
        try (NativeTelemetry telemetry = NativeTelemetry.start(restartedFull)) {
            assertEquals(TelemetryProfile.FULL, telemetry.activeProfile());
            assertEquals(TelemetryProfile.FULL, telemetry.configuredProfile());
            telemetry.updateProfile(TelemetryProfile.MICRO, Duration.ofSeconds(2));
            telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            assertEquals(TelemetryProfile.FULL, telemetry.activeProfile());
        }

        try (NativeTelemetry telemetry = NativeTelemetry.start(config)) {
            assertEquals(TelemetryProfile.MICRO, telemetry.activeProfile());
            String diagnostics = telemetry.diagnosticsJson();
            assertTrue(diagnostics.contains("\"active_profile_memory_ceiling_bytes\":0"));
            assertTrue(diagnostics.contains("\"profile_release_pending\":false"));
            assertTrue(diagnostics.contains("\"jvm_probe_registered\":false"));
        }
    }

    private static void awaitDiagnostic(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.isRegularFile(path) && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(Files.isRegularFile(path), "Native diagnostic did not publish " + path);
        assertTrue(Files.size(path) > 0L, "Native diagnostic was empty: " + path);
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

    private static final class DeferredTrackingException extends IllegalStateException {
        private final CountDownLatch captured = new CountDownLatch(1);
        private volatile long captureThreadId = -1L;

        private DeferredTrackingException() {
            super("integration failure");
        }

        @Override
        public String getMessage() {
            captureThreadId = Thread.currentThread().threadId();
            captured.countDown();
            return super.getMessage();
        }

        private boolean awaitCapture() throws InterruptedException {
            return captured.await(2, TimeUnit.SECONDS);
        }
    }
}
