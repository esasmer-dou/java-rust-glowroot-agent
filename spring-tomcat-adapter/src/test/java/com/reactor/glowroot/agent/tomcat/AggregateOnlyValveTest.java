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
import static org.junit.jupiter.api.Assertions.*;

class AggregateOnlyValveTest {
    @Test void microCompletionReadsOnlyTheRouteAttribute() throws Exception { run(false); }
    @Test void microErrorsKeepTheirStatusAndOriginalException() throws Exception { run(true); }

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
        TelemetryConfig config = new TelemetryConfig("http://127.0.0.1:1", "test", "test",
                "localhost", "21", "OpenJ9", "test", 1L, 1L, 60_000, 100, 100, 500, 256, 0, 8, 16_384);
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(new BoundedHttpTelemetry(sink, config));
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
        assertEquals(1, counts[0]);
        assertEquals(failed ? 500 : 200, counts[1]);
        if (failed) assertSame(failure, captured[0]); else assertNull(captured[0]);
    }
}
