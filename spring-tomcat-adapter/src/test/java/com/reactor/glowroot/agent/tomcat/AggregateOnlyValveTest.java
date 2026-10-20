package com.reactor.glowroot.agent.tomcat;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class AggregateOnlyValveTest {
    @Test void microCompletionReadsOnlyTheRouteAttribute() throws Exception { run(false); }
    @Test void microErrorsKeepTheirStatusAndOriginalException() throws Exception { run(true); }
    @Test void terminalCompletionRetainsConfiguredSlowTraceMetadata() {
        int[] details = new int[1];
        ServletException failure = new ServletException("provider timeout");
        HttpTelemetrySink sink = new HttpTelemetrySink() {
            public int registerHttpRoute(String method, String route) {
                assertEquals("/orders/{id}", route); return 1;
            }
            public void recordHttp(int slot, int status, long duration, int weight) {
                fail("Expected configured slow-trace details");
            }
            public void recordHttpDetail(int slot, int status, long duration, int weight,
                    String method, String controller, long cpu, long blocked, long waited,
                    long allocated, Throwable error) {
                assertEquals(500, status); assertEquals(659_600_000L, duration);
                assertEquals("POST", method); assertEquals("OrderController.create", controller);
                assertSame(failure, error); details[0]++;
            }
        };
        TelemetryConfig config = new TelemetryConfig("http://127.0.0.1:1", "test", "test",
                "localhost", "21", "OpenJ9", "test", 1L, 1L, 60_000, 100, 100, 500, 256, 2, 8, 16_384);
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(new BoundedHttpTelemetry(sink, config));
        java.util.Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern", "/orders/{id}");
        attributes.put(com.reactor.glowroot.agent.http.HttpTraceMetadata.CONTROLLER_ATTRIBUTE, "OrderController.create");
        attributes.put(com.reactor.glowroot.agent.http.HttpTraceMetadata.ERROR_ATTRIBUTE, failure);
        Request request = new Request(new Connector()) {
            public Object getAttribute(String name) { return attributes.get(name); }
            public void removeAttribute(String name) { attributes.remove(name); }
            public String getMethod() { return "POST"; }
        };
        Response response = new Response();
        response.setCoyoteResponse(new org.apache.coyote.Response());
        response.setStatus(500);
        valve.log(request, response, 659_600_000L);
        assertEquals(1, details[0]);
    }
    @Test void lateTransportErrorsPreserveTheirOriginalException() {
        int[] counts = new int[1];
        Throwable[] captured = new Throwable[1];
        IOException failure = new IOException("peer disconnected during flush");
        HttpTelemetrySink sink = new HttpTelemetrySink() {
            public int registerHttpRoute(String method, String route) { return 1; }
            public void recordHttp(int slot, int status, long duration, int weight) {
                assertEquals(500, status); assertEquals(123L, duration); counts[0]++;
            }
            public void recordError(int slot, long duration, Throwable error) { captured[0] = error; }
        };
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(new BoundedHttpTelemetry(sink, config()));
        Response response = new Response();
        response.setCoyoteResponse(new org.apache.coyote.Response());
        response.getCoyoteResponse().setErrorException(failure);
        valve.log(null, response, 123L);
        assertEquals(1, counts[0]);
        assertSame(failure, captured[0]);
    }

    @Test void malformedRequestsAndResponseRecyclingStayFailOpen() {
        int[] counts = new int[1];
        HttpTelemetrySink sink = new HttpTelemetrySink() {
            public int registerHttpRoute(String method, String route) {
                assertEquals(BoundedHttpTelemetry.UNMATCHED_ROUTE, route); return 1;
            }
            public void recordHttp(int slot, int status, long duration, int weight) {
                assertEquals(500, status); assertEquals(0L, duration); counts[0]++;
                throw new IllegalStateException("observer unavailable");
            }
        };
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(new BoundedHttpTelemetry(sink, config()));
        assertDoesNotThrow(() -> valve.log(null, null, -1L));
        assertEquals(1, counts[0]);
        assertFalse(valve.getRequestAttributesEnabled());
        valve.setRequestAttributesEnabled(true);
        assertTrue(valve.getRequestAttributesEnabled());
    }

    private static TelemetryConfig config() {
        return new TelemetryConfig("http://127.0.0.1:1", "test", "test",
                "localhost", "21", "OpenJ9", "test", 1L, 1L, 60_000, 100, 100, 500, 256, 0, 8, 16_384);
    }

    private static void run(boolean failed) throws Exception {
        int[] counts = new int[2];
        Throwable[] captured = new Throwable[1];
        HttpTelemetrySink sink = new HttpTelemetrySink() {
            public int registerHttpRoute(String method, String route) {
                assertEquals("/orders/{id}", route); return 1;
            }
            public void recordHttp(int slot, int status, long duration, int weight) {
                counts[0]++; counts[1] = status;
            }
            public void recordError(int slot, long duration, Throwable error) { captured[0] = error; }
        };
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(new BoundedHttpTelemetry(sink, config()));
        ServletException failure = new ServletException("provider failed");
        valve.setNext(new ValveBase(true) {
            public void invoke(Request request, Response response) throws ServletException {
                if (failed) throw failure;
            }
        });
        Request request = new Request(new Connector()) {
            public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
            public boolean isAsyncStarted() { return false; }
            public Object getAttribute(String name) {
                if (failed && name.equals(com.reactor.glowroot.agent.http.HttpTraceMetadata.ERROR_ATTRIBUTE)) return failure;
                if (name.equals("org.springframework.web.servlet.HandlerMapping.bestMatchingPattern")) {
                    return "/orders/{id}";
                }
                throw new AssertionError("Aggregate-only completion read diagnostic metadata: " + name);
            }
            public void removeAttribute(String name) {
                throw new AssertionError("Aggregate-only completion removed diagnostic metadata: " + name);
            }
        };
        request.setCoyoteRequest(new org.apache.coyote.Request());
        Response response = new Response();
        response.setCoyoteResponse(new org.apache.coyote.Response());
        if (failed) assertSame(failure, assertThrows(ServletException.class, () -> valve.invoke(request, response)));
        else valve.invoke(request, response);
        assertEquals(0, counts[0], "Only the server's terminal callback may emit a transaction");
        if (failed) response.setStatus(500);
        valve.log(request, response, 659_600_000L);
        assertEquals(1, counts[0]);
        assertEquals(failed ? 500 : 200, counts[1]);
        if (failed) assertSame(failure, captured[0]); else assertNull(captured[0]);
    }
}
