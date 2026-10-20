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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
            assertSame(config, telemetry.configuration(), "Adapters must reuse the validated startup configuration");
            assertEquals(TelemetryProfile.MICRO, telemetry.configuredProfile());
            int slot = telemetry.registerHttpRoute("GET", "/integration/{id}");
            telemetry.recordHttp(slot, 200, 100_000, 256);
            String diagnostics = telemetry.diagnosticsJson();
            if (Boolean.getBoolean("reactor.glowroot.test.cached-init")) {
                assertTrue(diagnostics.contains("\"jvm_init_source\":"), diagnostics);
                assertTrue(!diagnostics.contains("\"jvm_init_source\":\"unavailable\""), diagnostics);
                if (System.getProperty("java.vm.name", "").contains("OpenJ9")) {
                    assertTrue(diagnostics.contains("\"jvm_init_source\":\"openj9-bootstrap/"), diagnostics);
                }
                var bytes = java.util.regex.Pattern.compile("\"jvm_init_retained_bytes\":(\\d+)").matcher(diagnostics);
                assertTrue(bytes.find(), diagnostics);
                long retained = Long.parseLong(bytes.group(1));
                assertTrue(retained > 0 && retained < 65_536, diagnostics);
            }
            assertTrue(diagnostics.contains("\"enabled\":true"));
            assertTrue(diagnostics.contains("\"registered_routes\":1"));
            if (Boolean.getBoolean("reactor.glowroot.test.operation-api")) {
                NativeOperation job = telemetry.operation("Scheduler", "CacheRefresh#run");
                NativeOperation provider = telemetry.operation("Dubbo Provider", "Catalog#find");
                assertTrue(job.isEnabled(), "Updated native operation API is required");
                assertTrue(provider.isEnabled());
                job.success(job.start());
                provider.failure(provider.start(), new IllegalArgumentException("provider failed"));
                assertTrue(telemetry.diagnosticsJson().contains("\"registered_routes\":3"));
                assertTrue(NativeTelemetry.current() == telemetry);
                verifyNonWebSchedulerAutoConfiguration(telemetry);
            }

            telemetry.updateProfile(TelemetryProfile.FULL, Duration.ofSeconds(2));
            assertEquals(TelemetryProfile.FULL, telemetry.activeProfile());
            try (NativeTelemetry sharedBaseline = NativeTelemetry.start(config)) {
                assertEquals(TelemetryProfile.MICRO, sharedBaseline.configuredProfile());
                assertEquals(TelemetryProfile.FULL, sharedBaseline.activeProfile());
            }
            assertTrue(NativeTelemetry.current() == telemetry);
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
                awaitDiagnostic(telemetry, threadDump);
                assertTrue(Files.readString(threadDump).contains(" Id="));

                assertTrue(telemetry.submitDiagnostic("heap-histogram", heapHistogram) > 0L);
                awaitDiagnostic(telemetry, heapHistogram);
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
        verifyPreparedSpringStartup(config, false);
        verifyPreparedSpringStartup(config, true);
    }

    private static void verifyPreparedSpringStartup(TelemetryConfig config, boolean earlyRegistration)
            throws Exception {
        try (NativeTelemetry prepared = NativeTelemetry.prepare(config, key -> null);
             var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            assertFalse(prepared.isStarted());
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("prepared-test", java.util.Map.of(
                            "reactor.glowroot.enabled", "true")));
            context.registerBean("existingTelemetry", NativeTelemetry.class, () -> prepared,
                    definition -> definition.setDestroyMethodName(""));
            context.registerBean("ordinarySingleton", Object.class, () -> {
                assertFalse(prepared.isStarted(), "Native activation preceded ordinary bean construction");
                if (earlyRegistration) {
                    int slot = prepared.registerHttpRoute("GET", "/integration/{id}");
                    assertTrue(prepared.isStarted(), "An early real registration was silently deferred");
                    prepared.recordHttp(slot, 200, 10_000, 0);
                }
                return new Object();
            });
            context.register(com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration.class);
            context.refresh();
            assertTrue(prepared.isStarted(), "Native runtime must be active before context readiness");
            assertSame(prepared, prepared.activate(), "Bootstrap activation must be idempotent");
            var resolver = NativeTelemetry.class.getDeclaredField("nativeProperties");
            resolver.setAccessible(true);
            assertNull(resolver.get(prepared), "Spring Environment was retained after native activation");
            try (NativeTelemetry unused = NativeTelemetry.prepare(config)) {
                assertFalse(unused.isStarted());
            }
            assertSame(prepared, NativeTelemetry.current());
            assertTrue(prepared.diagnosticsJson().contains("\"enabled\":true"),
                    "Closing an unused prepared handle stopped the active exporter");
        }
    }

    private static void verifyNonWebSchedulerAutoConfiguration(NativeTelemetry telemetry) throws Exception {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("operation-test", java.util.Map.of(
                            "reactor.glowroot.enabled", "true")));
            context.registerBean("existingTelemetry", NativeTelemetry.class, () -> telemetry,
                    definition -> definition.setDestroyMethodName(""));
            context.register(SchedulerFixture.class,
                    com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration.class);
            context.refresh();
            assertTrue(context.getBean(ScheduledJob.class).ticks.await(3, TimeUnit.SECONDS),
                    "Non-web Spring scheduler did not run");
            assertTrue(telemetry.diagnosticsJson().contains("\"registered_routes\":4"),
                    "Scheduling auto-configuration did not register a native transaction");
        }
        assertTrue(NativeTelemetry.current() == telemetry, "Closing a scheduler context closed the shared facade");
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.scheduling.annotation.EnableScheduling
    static class SchedulerFixture {
        @org.springframework.context.annotation.Bean
        ScheduledJob scheduledJob() { return new ScheduledJob(); }

        @org.springframework.context.annotation.Bean
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler operationsScheduler() {
            var scheduler = new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("operations-scheduler-");
            return scheduler;
        }
    }

    static class ScheduledJob {
        final CountDownLatch ticks = new CountDownLatch(3);
        @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10, scheduler = "operationsScheduler")
        public void refresh() {
            assertTrue(Thread.currentThread().getName().startsWith("operations-scheduler-"));
            ticks.countDown();
        }
    }

    private static void awaitDiagnostic(NativeTelemetry telemetry, Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        // Publishing the file precedes releasing the native diagnostic guard and temp link.
        // A profile downgrade or directory deletion must wait for both boundaries.
        while ((!Files.isRegularFile(path)
                || !telemetry.diagnosticsJson().contains("\"diagnostic_in_progress\":false"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(telemetry.diagnosticsJson().contains("\"diagnostic_in_progress\":false"),
                "Native diagnostic did not release its execution guard");
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
