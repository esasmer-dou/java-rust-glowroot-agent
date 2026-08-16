package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustGlowrootTomcatValveTest {

    @Test
    void recordsSampledSuccessFromTomcatRequestClock() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(recorder, config(1, 0, 4));
        valve.setNext(new TerminalValve(200, null));
        Exchange exchange = exchange();

        valve.invoke(exchange.request(), exchange.response());

        assertEquals(List.of("GET /orders/{id}"), recorder.routes);
        assertEquals(1, recorder.records.size());
        Record record = recorder.records.getFirst();
        assertEquals(200, record.status());
        assertEquals(1, record.sampleWeight());
        assertTrue(record.durationNanos() > 0L);
    }

    @Test
    void recordsUnhandledFailureExactlyAndRethrows() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(recorder, config(1024, 0, 4));
        valve.setNext(new TerminalValve(200, new ServletException("failed")));
        Exchange exchange = exchange();

        assertThrows(
                ServletException.class,
                () -> valve.invoke(exchange.request(), exchange.response()));

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.getFirst().status());
        assertEquals(0, recorder.records.getFirst().sampleWeight());
        assertEquals(1, recorder.errors);
    }

    @Test
    void keepsRouteRegistrationBounded() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootTomcatValve valve = new RustGlowrootTomcatValve(recorder, config(1, 0, 1));
        valve.setNext(new TerminalValve(200, null));
        Exchange first = exchange();
        valve.invoke(first.request(), first.response());
        Exchange second = exchange();
        second.request().setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/customers/{id}");
        valve.invoke(second.request(), second.response());

        assertEquals(1, recorder.routes.size());
        assertEquals(1, recorder.records.size());
    }

    private static Exchange exchange() {
        Connector connector = new Connector();
        Request request = new Request(connector);
        org.apache.coyote.Request coyoteRequest = new org.apache.coyote.Request();
        coyoteRequest.method().setString("GET");
        coyoteRequest.requestURI().setString("/orders/42");
        coyoteRequest.setStartTimeNanos(System.nanoTime() - 1_000_000L);
        request.setCoyoteRequest(coyoteRequest);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");

        Response response = new Response();
        response.setCoyoteResponse(new org.apache.coyote.Response());
        response.setRequest(request);
        request.setResponse(response);
        return new Exchange(request, response);
    }

    private static TelemetryConfig config(int sampleRate, int traceCapacity, int maxRoutes) {
        return new TelemetryConfig(
                "http://127.0.0.1:8181",
                "test::agent",
                "test-app",
                "localhost",
                "21",
                "OpenJ9",
                "test",
                1,
                1,
                60_000,
                1_000,
                2_000,
                500,
                sampleRate,
                traceCapacity,
                maxRoutes,
                65_536
        );
    }

    private record Exchange(Request request, Response response) {}

    private record Record(int status, long durationNanos, int sampleWeight) {}

    private static final class FakeRecorder implements HttpTelemetryRecorder {
        private final List<String> routes = new ArrayList<>();
        private final List<Record> records = new ArrayList<>();
        private int errors;

        @Override
        public int registerHttpRoute(String method, String route) {
            routes.add(method + ' ' + route);
            return routes.size() - 1;
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.add(new Record(status, durationNanos, sampleWeight));
        }

        @Override
        public void recordError(int slot, long durationNanos, Throwable error) {
            errors++;
        }
    }

    private static final class TerminalValve extends ValveBase {
        private final int status;
        private final ServletException failure;

        private TerminalValve(int status, ServletException failure) {
            this.status = status;
            this.failure = failure;
        }

        @Override
        public void invoke(Request request, Response response) throws IOException, ServletException {
            response.setStatus(status);
            if (failure != null) throw failure;
        }
    }
}
