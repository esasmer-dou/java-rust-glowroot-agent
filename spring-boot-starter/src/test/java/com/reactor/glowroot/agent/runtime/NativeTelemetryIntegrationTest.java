package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;

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
            int slot = telemetry.registerHttpRoute("GET", "/integration/{id}");
            telemetry.recordHttp(slot, 200, 100_000, 256);
            String diagnostics = telemetry.diagnosticsJson();
            assertTrue(diagnostics.contains("\"enabled\":true"));
            assertTrue(diagnostics.contains("\"registered_routes\":1"));
        }
    }
}
