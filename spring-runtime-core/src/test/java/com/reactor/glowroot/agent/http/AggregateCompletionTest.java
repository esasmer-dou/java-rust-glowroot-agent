package com.reactor.glowroot.agent.http;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AggregateCompletionTest {
    @Test void preservesExactCountsErrorsAndNativeRouteCaching() {
        Sink sink = new Sink();
        BoundedHttpTelemetry telemetry = new BoundedHttpTelemetry(sink, config());
        assertTrue(telemetry.aggregateOnly());
        for (int i = 0; i < 100; i++) telemetry.recordAggregate("/orders/{id}", 200, 123, null);
        Throwable failure = new IllegalStateException("failed");
        telemetry.recordAggregate("/orders/{id}", 200, -1, failure);
        assertEquals(1, sink.registrations);
        assertEquals(101, sink.count);
        assertEquals(500, sink.status);
        assertEquals(0, sink.duration);
        assertSame(failure, sink.error);
    }

    @Test void dynamicDiagnosticsAlwaysLeavesTheAggregateOnlyPath() {
        BoundedHttpTelemetry telemetry = new BoundedHttpTelemetry(new Sink(), config());
        try {
            HttpRequestTraceState.setEnabled(true);
            assertFalse(telemetry.aggregateOnly());
        } finally { HttpRequestTraceState.setEnabled(false); }
        assertTrue(telemetry.aggregateOnly());
    }

    @Test void nativeFailureDoesNotEscapeIntoTheApplication() {
        BoundedHttpTelemetry telemetry = new BoundedHttpTelemetry(new Sink() {
            @Override public void recordHttp(int slot, int status, long duration, int weight) {
                throw new IllegalStateException("native stopped");
            }
        }, config());
        assertDoesNotThrow(() -> telemetry.recordAggregate("/orders", 200, 123, null));
    }

    @Test void aggregateOnlyAdaptersDoNotInitializeThreadLocalOrSamplerState() {
        var telemetry = new BoundedHttpTelemetry(new Sink(), config());
        assertEquals(0, telemetry.takeThreadObservation());
        for (int i = 0; i < 1_000; i++) {
            telemetry.observation(200);
            telemetry.beginObservation();
            telemetry.recordAggregate("/orders", 200, 100, null);
        }
        assertFalse(telemetry.threadStateInitialized());
    }

    @Test void genericMvcTimingRemainsThreadBoundWithoutTraceSampling() throws Exception {
        var telemetry = new BoundedHttpTelemetry(new Sink(), config());
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                assertEquals(0, telemetry.takeThreadObservation());
                telemetry.beginThreadObservation();
                long token = telemetry.takeThreadObservation();
                assertNotEquals(0, token);
                assertTrue(telemetry.shouldRecord(telemetry.observationFlags(token), 200, 1, null));
                assertTrue(telemetry.elapsedNanos(token) >= 0);
                assertEquals(0, telemetry.takeThreadObservation());
            }));
            for (var task : tasks) task.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertTrue(telemetry.threadStateInitialized());
    }

    private static TelemetryConfig config() {
        return new TelemetryConfig("http://127.0.0.1:1", "test", "test", "localhost",
                "21", "OpenJ9", "test", 1L, 1L, 60_000, 100, 100, 500, 256, 0, 8, 16_384);
    }

    private static class Sink implements HttpTelemetrySink {
        int registrations, count, status;
        long duration;
        Throwable error;
        public int registerHttpRoute(String method, String route) { registrations++; return 1; }
        public void recordHttp(int slot, int status, long duration, int weight) {
            assertEquals(0, weight);
            this.count++; this.status = status; this.duration = duration;
        }
        public void recordError(int slot, long duration, Throwable error) { this.error = error; }
    }
}
