package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeTelemetryIntegrationTest {

    @Test
    void loadsThePackagedPlatformBinaryAndRunsTheBoundedLifecycle() {
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
            statement.recordFailure(failedCall, new IllegalStateException("integration failure"));

            telemetry.restoreConfiguredProfile(Duration.ofSeconds(2));
            assertEquals(TelemetryProfile.MICRO, telemetry.activeProfile());
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
}
