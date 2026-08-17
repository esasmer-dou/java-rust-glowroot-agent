package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustGlowrootInterceptorTest {

    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";

    @Test
    void samplesSuccessesAndKeepsErrorsExactWithoutPerRequestRouteRegistration() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(4, 0, 4));

        for (int index = 0; index < 4; index++) {
            invoke(interceptor, 200, "/orders/{id}", null);
        }
        invoke(interceptor, 500, "/orders/{id}", null);

        assertEquals(1, recorder.registrations);
        assertEquals(2, recorder.records.size());
        assertEquals(4, recorder.records.get(0).sampleWeight());
        assertEquals(200, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(1).sampleWeight());
        assertEquals(500, recorder.records.get(1).status());
    }

    @Test
    void recordsUnhandledFailuresAsExactErrors() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1024, 0, 4));

        invoke(interceptor, 200, "/orders/{id}", new IllegalStateException("failed"));

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    @Test
    void readsTheResolvedRouteAndMappedStatusAfterDispatch() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 4));

        invoke(interceptor, 422, "/orders/{id}", null);

        assertEquals(List.of("GET /orders/{id}"), recorder.routes);
        assertEquals(422, recorder.records.get(0).status());
    }

    @Test
    void boundsTheJavaRouteCacheBeforeCallingNativeRegistration() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));

        invoke(interceptor, 200, "/orders/{id}", null);
        invoke(interceptor, 200, "/customers/{id}", null);

        assertEquals(1, recorder.registrations);
        assertEquals(1, recorder.records.size());
    }

    @Test
    void resolvesHashCollisionsWithoutReregisteringRoutes() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 2));

        invoke(interceptor, 200, "/FB", null);
        invoke(interceptor, 200, "/Ea", null);
        invoke(interceptor, 200, "/FB", null);

        assertEquals(2, recorder.registrations);
        assertEquals(3, recorder.records.size());
        assertEquals(0, recorder.records.get(0).slot());
        assertEquals(1, recorder.records.get(1).slot());
        assertEquals(0, recorder.records.get(2).slot());
    }

    @Test
    void publishesOneRouteSlotAcrossConcurrentSampledRequests() throws Exception {
        ConcurrentRecorder recorder = new ConcurrentRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 4));
        int workers = 8;
        int requestsPerWorker = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(workers);

        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                executor.execute(() -> {
                    try {
                        start.await();
                        for (int request = 0; request < requestsPerWorker; request++) {
                            invoke(interceptor, 200, "/orders/{id}", null);
                        }
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finished.await(10, TimeUnit.SECONDS));
        }

        assertEquals(1, recorder.registrations.get());
        assertEquals(workers * requestsPerWorker, recorder.records.get());
    }

    @Test
    void preservesTheOriginalSampleAcrossAsyncRedispatch() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        request.setDispatcherType(DispatcherType.ASYNC);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, recorder.records.size());
        assertEquals(200, recorder.records.get(0).status());
    }

    @Test
    void recordsAnUnsampledAsyncFailureExactlyOnceWithoutSamplingWeight() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(8, 0, 1));
        advancePastSample(interceptor, recorder);
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterConcurrentHandlingStarted(request, response, new Object());
        request.setDispatcherType(DispatcherType.ASYNC);
        response.setStatus(503);
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, recorder.records.size());
        assertEquals(503, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    @Test
    void ignoresASecondContainerErrorDispatch() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        response.setStatus(500);
        interceptor.afterCompletion(request, response, new Object(), null);
        request.setDispatcherType(DispatcherType.ERROR);
        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, recorder.records.size());
    }

    @Test
    void recordsAnUnmatched404FromThePortableErrorDispatch() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
        response.setStatus(404);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(List.of("GET <unmatched>"), recorder.routes);
        assertEquals(1, recorder.records.size());
        assertEquals(404, recorder.records.getFirst().status());
    }

    @Test
    void recordsAnUnmatchedContainerFailureExactly() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1024, 0, 1));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/broken");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(
                RequestDispatcher.ERROR_EXCEPTION,
                new IllegalStateException("container failure"));
        response.setStatus(500);

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.getFirst().status());
        assertEquals(0, recorder.records.getFirst().sampleWeight());
    }

    @Test
    void keepsTheCompatibilityTimeoutStatusExact() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        MockHttpServletRequest request = request("/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.complete(request, response, new IllegalStateException("timeout"), 504);

        assertEquals(1, recorder.records.size());
        assertEquals(504, recorder.records.get(0).status());
        assertEquals(0, recorder.records.get(0).sampleWeight());
    }

    @Test
    void keepsSynchronousRequestsOutOfTheServletAttributeTable() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(1, 0, 1));
        TrackingRequest request = new TrackingRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(0, request.observationAttributeWrites);
        assertEquals(1, recorder.records.size());
    }

    @Test
    void skipsMethodAndRouteLookupForAnUnsampledSuccessfulRequest() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootInterceptor interceptor = new RustGlowrootInterceptor(recorder, config(8, 0, 1));
        advancePastSample(interceptor, recorder);
        TrackingRequest request = new TrackingRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, new Object());
        interceptor.afterCompletion(request, response, new Object(), null);

        assertEquals(0, request.methodReads);
        assertEquals(0, request.routeAttributeReads);
        assertEquals(0, recorder.records.size());
    }

    private static void invoke(
            RustGlowrootInterceptor interceptor,
            int status,
            String route,
            Exception exception) throws Exception {
        MockHttpServletRequest request = request(route);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        response.setStatus(status);
        interceptor.afterCompletion(request, response, new Object(), exception);
    }

    private static void advancePastSample(
            RustGlowrootInterceptor interceptor,
            FakeRecorder recorder) throws Exception {
        while (recorder.records.isEmpty()) {
            invoke(interceptor, 200, "/orders/{id}", null);
        }
        recorder.records.clear();
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

    private static final class FakeRecorder implements HttpTelemetrySink {
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

    private static final class TrackingRequest extends MockHttpServletRequest {
        private int observationAttributeWrites;
        private int methodReads;
        private int routeAttributeReads;

        private TrackingRequest() {
            super("GET", "/orders/42");
        }

        @Override
        public String getMethod() {
            methodReads++;
            return super.getMethod();
        }

        @Override
        public Object getAttribute(String name) {
            if (HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE.equals(name)) routeAttributeReads++;
            return super.getAttribute(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            if (OBSERVATION_ATTRIBUTE.equals(name)) observationAttributeWrites++;
            super.setAttribute(name, value);
        }

        @Override
        public void removeAttribute(String name) {
            if (OBSERVATION_ATTRIBUTE.equals(name)) observationAttributeWrites++;
            super.removeAttribute(name);
        }
    }

    private static final class ConcurrentRecorder implements HttpTelemetrySink {
        private final AtomicInteger registrations = new AtomicInteger();
        private final AtomicInteger records = new AtomicInteger();

        @Override
        public int registerHttpRoute(String method, String route) {
            return registrations.getAndIncrement();
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.incrementAndGet();
        }
    }

    private record Record(int slot, int status, int sampleWeight) {}
}
