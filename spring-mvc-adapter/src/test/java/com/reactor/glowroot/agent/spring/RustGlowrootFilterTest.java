package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("deprecation")
class RustGlowrootFilterTest {

    @Test
    void preservesThePublishedSynchronousFilterContract() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootFilter filter = new RustGlowrootFilter(recorder, config());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> response.setStatus(503));

        assertEquals(List.of("/orders/{id}"), recorder.routes);
        assertEquals(1, recorder.records.size());
        assertEquals(503, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    private static TelemetryConfig config() {
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
                1,
                0,
                4,
                65_536
        );
    }

    private static final class FakeRecorder implements HttpTelemetrySink {
        private final List<String> routes = new ArrayList<>();
        private final List<Record> records = new ArrayList<>();

        @Override
        public int registerHttpRoute(String method, String route) {
            routes.add(route);
            return routes.size() - 1;
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.add(new Record(status, sampleWeight));
        }
    }

    private record Record(int status, int sampleWeight) {}
}
