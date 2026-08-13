package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.DispatcherType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RustGlowrootInterceptorTest {

    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";

    @Test
    void samplesSuccessesAndKeepsErrorsExactWithoutPerRequestRouteRegistration() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(4, 0, 4));

        for (int index = 0; index < 4; index++) {
            invoke(interceptor, 200, "/orders/{id}");
        }
        invoke(interceptor, 500, "/orders/{id}");

        assertEquals(1, recorder.registrations);
        assertEquals(2, recorder.records.size());
        assertEquals(4, recorder.records.get(0).sampleWeight());
        assertEquals(200, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(1).sampleWeight());
        assertEquals(500, recorder.records.get(1).status());
    }

    @Test
    void boundsTheJavaRouteCacheBeforeCallingNativeRegistration() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));

        invoke(interceptor, 200, "/orders/{id}");
        invoke(interceptor, 200, "/customers/{id}");

        assertEquals(1, recorder.registrations);
        assertEquals(1, recorder.records.size());
    }

    @Test
    void recordsThrownFailuresAsExactErrors() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1024, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, new Object(), new IllegalStateException("failed"));

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    @Test
    void transfersOnlySampledStateAcrossAnAsyncDispatch() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        request.setDispatcherType(DispatcherType.ASYNC);
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, recorder.registrations);
        assertEquals(1, recorder.records.size());
        assertEquals(1, recorder.records.get(0).sampleWeight());
    }

    @Test
    void leavesUnsampledAsyncRequestsWithoutObservationAllocations() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(4, 0, 1));
        int observations = 0;

        for (int index = 0; index < 4; index++) {
            MockHttpServletRequest request = request("/orders/{id}");
            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(request, response, new Object());
            interceptor.afterConcurrentHandlingStarted(request, response, new Object());
            if (request.getAttribute(OBSERVATION_ATTRIBUTE) != null) observations++;
            request.setDispatcherType(DispatcherType.ASYNC);
            interceptor.afterCompletion(request, response, new Object(), null);
        }

        assertEquals(1, observations);
        assertEquals(1, recorder.records.size());
        assertEquals(4, recorder.records.get(0).sampleWeight());
    }

    private static void invoke(RustGlowrootInterceptor interceptor, int status, String route) {
        MockHttpServletRequest request = request(route);
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), null);
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
