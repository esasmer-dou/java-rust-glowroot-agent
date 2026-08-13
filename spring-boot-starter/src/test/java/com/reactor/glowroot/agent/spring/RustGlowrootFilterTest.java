package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

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
    void boundsTheJavaRouteCacheBeforeCallingNativeRegistration() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config(1, 0, 1));

        invoke(filter, 200, "/orders/{id}");
        invoke(filter, 200, "/customers/{id}");

        assertEquals(1, recorder.registrations);
        assertEquals(1, recorder.records.size());
    }

    private static void invoke(RustGlowrootFilter filter, int status, String route) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((MockHttpServletResponse) res).setStatus(status));
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
        private final List<Record> records = new ArrayList<>();

        @Override
        public int registerHttpRoute(String method, String route) {
            return registrations++;
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.add(new Record(slot, status, sampleWeight));
        }
    }

    private record Record(int slot, int status, int sampleWeight) {}
}
