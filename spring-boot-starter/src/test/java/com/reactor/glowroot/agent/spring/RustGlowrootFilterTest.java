package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RustGlowrootFilterTest {

    @Test
    void samplesSuccessesAndKeepsErrorsExactWithoutPerRequestRouteRegistration() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(4, 0, 4));

        for (int index = 0; index < 4; index++) {
            invoke(filter, 200, "/orders/{id}");
        }
        invoke(filter, 500, "/orders/{id}");

        assertEquals(1, recorder.registrations);
        assertEquals(2, recorder.records.size());
        assertEquals(4, recorder.records.get(0).sampleWeight());
        assertEquals(200, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(1).sampleWeight());
        assertEquals(500, recorder.records.get(1).status());
    }

    @Test
    void recordsUnhandledFailuresAsExactErrors() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(1024, 0, 4));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new ServletException("failed");
            });
        } catch (IOException | ServletException expected) {
            // The filter records and preserves the original application failure.
        }

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    @Test
    void readsTheResolvedRouteAndMappedStatusAfterTheDispatcherReturns() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(1, 0, 4));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
            ((MockHttpServletResponse) res).setStatus(422);
        });

        assertEquals(List.of("GET /orders/{id}"), recorder.routes);
        assertEquals(422, recorder.records.get(0).status());
    }

    @Test
    void boundsTheJavaRouteCacheBeforeCallingNativeRegistration() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(1, 0, 1));

        invoke(filter, 200, "/orders/{id}");
        invoke(filter, 200, "/customers/{id}");

        assertEquals(1, recorder.registrations);
        assertEquals(1, recorder.records.size());
    }

    @Test
    void recordsAsyncCompletionOnce() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(1, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> req.startAsync());
        AsyncContext async = request.getAsyncContext();
        async.complete();

        assertEquals(1, recorder.records.size());
        assertEquals(200, recorder.records.get(0).status());
    }

    private static void invoke(RustGlowrootFilter filter, int status, String route) throws Exception {
        MockHttpServletRequest request = request(route);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(status));
    }

    private static MockHttpServletRequest request(String route) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        return request;
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

    private static final class FakeRecorder implements HttpTelemetryRecorder {
        private int registrations;
        private final List<String> routes = new ArrayList<>();
        private final List<Record> records = new ArrayList<>();

        @Override
        public int registerHttpRoute(String method, String route) {
            routes.add(method + ' ' + route);
            return registrations++;
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.add(new Record(slot, status, sampleWeight));
        }
    }

    private record Record(int slot, int status, int sampleWeight) {}
}
