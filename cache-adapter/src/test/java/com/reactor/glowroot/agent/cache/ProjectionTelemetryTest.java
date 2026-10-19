package com.reactor.glowroot.agent.cache;

import com.reactor.glowroot.agent.runtime.StandaloneTelemetry;
import com.reactor.glowroot.agent.runtime.NativeOperation;
import com.reactor.glowroot.agent.runtime.OperationTelemetrySink;
import com.reactor.rust.cache.scheduler.ProjectionRefreshScheduler.ProjectionRefresher;
import com.reactor.rust.cache.scheduler.ProjectionRefreshResult;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionTelemetryTest {
    @Test void recordsBusinessResultsAndPreservesResultIdentity() throws Exception {
        var outcomes = new java.util.ArrayList<Throwable>();
        var operation = NativeOperation.register(sink(outcomes), "Scheduler", "cache-refresh:detail");
        for (var result : java.util.List.of(ProjectionRefreshResult.published("detail", "v1", 2),
                ProjectionRefreshResult.skipped("detail"), new ProjectionRefreshResult("detail", false, false, "", 0))) {
            var wrapped = ProjectionTelemetry.observed(Map.of("detail", operation), (p, l, t) -> result);
            assertSame(result, wrapped.refresh("detail", "lock", 1000));
        }
        assertNull(outcomes.get(0));
        assertNull(outcomes.get(1));
        assertNotNull(outcomes.get(2));
        assertEquals(0, outcomes.get(2).getStackTrace().length);
    }

    @Test void originalFailuresEscapeAndUnknownNamesDoNotReachBusinessCode() {
        var outcomes = new java.util.ArrayList<Throwable>();
        var operation = NativeOperation.register(sink(outcomes), "Scheduler", "cache-refresh:detail");
        var error = new IllegalStateException("database unavailable");
        var wrapped = ProjectionTelemetry.observed(Map.of("detail", operation), (p, l, t) -> { throw error; });
        assertSame(error, assertThrows(IllegalStateException.class, () -> wrapped.refresh("detail", "lock", 1000)));
        assertSame(error, outcomes.getFirst());
        assertThrows(IllegalArgumentException.class, () -> wrapped.refresh("unknown", "lock", 1000));
        assertEquals(1, outcomes.size());
    }

    private OperationTelemetrySink sink(java.util.List<Throwable> outcomes) {
        return new OperationTelemetrySink() {
            public int registerOperation(String type, String name) { return 0; }
            public void recordOperation(int slot, long duration, Throwable error) {
                assertTrue(duration >= 0);
                outcomes.add(error);
            }
        };
    }

    @Test void disabledReturnsOriginalWithoutAWrapperOrNativeInitialization() {
        try(var telemetry=StandaloneTelemetry.startIfEnabled(key->"false")) {
            ProjectionRefresher refresher=(p,l,t)->null;
            assertSame(refresher,ProjectionTelemetry.wrap(telemetry,Set.of("detail"),refresher));
        }
    }
    @Test void cacheAndAgentCanShareTheNativeRuntimeWithoutManifestCollision() {
        var config=Map.of("reactor.glowroot.enabled","true","reactor.glowroot.collector.address","http://127.0.0.1:1",
                "reactor.glowroot.agent.id","cache-adapter-test","reactor.glowroot.connect-timeout-ms","100",
                "reactor.glowroot.request-timeout-ms","100");
        try(var telemetry=StandaloneTelemetry.startIfEnabled(config::get)) {
            assertTrue(telemetry.enabled());
            var operation=telemetry.operation("Scheduler","cache-refresh:detail");
            assertTrue(operation.isEnabled());
            operation.complete(operation.start(),false);
            assertThrows(IllegalArgumentException.class,()->ProjectionTelemetry.wrap(telemetry,Set.of(),(p,l,t)->null));
        }
    }
}
