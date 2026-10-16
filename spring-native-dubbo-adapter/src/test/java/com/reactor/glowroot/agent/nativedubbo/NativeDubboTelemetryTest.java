package com.reactor.glowroot.agent.nativedubbo;

import static org.junit.jupiter.api.Assertions.*;
import com.reactor.glowroot.agent.runtime.OperationTelemetrySink;
import com.reactor.rust.dubbo.runtime.DubboProviderTelemetry;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class NativeDubboTelemetryTest {
    @Test void descriptorsAreRegisteredOnceAndOnlyCompletionsAreCounted() {
        var results = new ArrayList<Throwable>();
        var telemetry = new NativeDubboTelemetry(new OperationTelemetrySink() {
            public int registerOperation(String type, String name) {
                assertEquals("Dubbo Provider", type);
                assertEquals("Catalog#find", name);
                return 1;
            }
            public void recordOperation(int slot, long duration, Throwable error) { results.add(error); }
        });
        var method = telemetry.method("Catalog", "find");
        long token = method.start();
        assertTrue(results.isEmpty());
        method.complete(token, null);
        var failure = new IllegalArgumentException("missing");
        method.complete(method.start(), failure);
        assertEquals(2, results.size());
        assertSame(failure, results.get(1));
        assertSame(DubboProviderTelemetry.Method.NONE, new NativeDubboTelemetry(null).method("Catalog", "find"));
    }
}
