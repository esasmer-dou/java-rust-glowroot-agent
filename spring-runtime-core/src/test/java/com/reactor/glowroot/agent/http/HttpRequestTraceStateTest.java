package com.reactor.glowroot.agent.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestTraceStateTest {

    @AfterEach
    void disable() {
        HttpRequestTraceState.setEnabled(false);
        HttpRequestTraceState.setCurrentLookup(null);
    }

    @Test
    void remainsAllocationFreeWhenDiagnosticCorrelationIsDisabled() {
        HttpRequestTraceState.setEnabled(false);
        assertNull(HttpRequestTraceState.open());
        assertNull(HttpRequestTraceState.beginDubbo("CatalogService", "list"));
    }

    @Test
    void aggregatesDubboCallsWithoutRetainingArgumentsOrResults() {
        HttpRequestTraceState.setEnabled(true);
        HttpRequestTraceState state = HttpRequestTraceState.open();
        assertNotNull(state);
        HttpRequestTraceState.setCurrentLookup(() -> state);
        state.beginController();

        HttpRequestTraceState.DubboObservation first =
                HttpRequestTraceState.beginDubbo("com.example.CatalogService", "list");
        HttpRequestTraceState.DubboObservation second =
                HttpRequestTraceState.beginDubbo("com.example.CatalogService", "find");
        assertNotNull(first);
        assertNotNull(second);
        first.complete(false);
        first.complete(true);
        second.complete(true);
        state.completeController();

        HttpRequestTraceState.Snapshot snapshot = state.snapshot(10_000_000L);
        assertEquals("com.example.CatalogService#list()", snapshot.dubboOperation());
        assertEquals(2L, snapshot.dubboCount());
        assertEquals(1L, snapshot.dubboErrors());
        assertTrue(snapshot.dubboDurationNanos() >= 0L);
        assertTrue(snapshot.controllerDurationNanos() >= 0L);
        assertNull(HttpRequestTraceState.current());
    }
}
