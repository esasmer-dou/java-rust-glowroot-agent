package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.HashMap;
import java.util.Map;

/** Low-allocation Spring MVC request timing adapter for the Rust telemetry runtime. */
public final class RustGlowrootInterceptor implements AsyncHandlerInterceptor {

    private static final int DISABLED_SLOT = -1;
    private static final String UNMATCHED_ROUTE = "<unmatched>";
    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";

    private final HttpTelemetryRecorder telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final boolean slowTraceEnabled;
    private final long slowThresholdNanos;
    private final int maxRoutes;
    private final ThreadLocal<RequestState> requestState;
    private final Map<String, Integer> routeSlots;

    /**
     * Creates the low-allocation Spring MVC interceptor.
     *
     * @param telemetry process telemetry handle
     * @param config bounded telemetry configuration
     */
    public RustGlowrootInterceptor(NativeTelemetry telemetry, TelemetryConfig config) {
        this(new NativeRecorder(telemetry), config);
    }

    RustGlowrootInterceptor(HttpTelemetryRecorder telemetry, TelemetryConfig config) {
        this.telemetry = telemetry;
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1;
        this.slowTraceEnabled = config.traceCapacity() != 0;
        this.slowThresholdNanos = !slowTraceEnabled
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.maxRoutes = config.maxRoutes();
        this.requestState = ThreadLocal.withInitial(() ->
                new RequestState(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new HashMap<>(Math.min(16, maxRoutes));
    }

    private record NativeRecorder(NativeTelemetry telemetry) implements HttpTelemetryRecorder {
        @Override
        public int registerHttpRoute(String method, String route) {
            return telemetry.registerHttpRoute(method, route);
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            telemetry.recordHttp(slot, status, durationNanos, sampleWeight);
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Spring invokes the interceptor again for the final ASYNC dispatch. The initial request
        // already owns the timing state, so a second sample would double-count the same request.
        if (request.getDispatcherType() == DispatcherType.ASYNC) return true;

        RequestState state = requestState.get();
        boolean sampled = state.next(sampleMask);
        if (sampled || slowTraceEnabled) {
            state.begin(sampled ? sampleRate : RequestState.SLOW_ONLY);
        }
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        RequestState state = requestState.get();
        if (state.observationWeight != RequestState.NOT_OBSERVING) {
            request.setAttribute(
                    OBSERVATION_ATTRIBUTE,
                    new Observation(state.observationWeight, state.startedAtNanos)
            );
            state.clearObservation();
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception failure) {
        int status = failure == null ? response.getStatus() : 500;
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            completeAsync(request, status);
            return;
        }

        RequestState state = requestState.get();
        if (state.observationWeight == RequestState.NOT_OBSERVING) {
            if (status >= 500) record(request, status, 0L, false);
            return;
        }
        try {
            long durationNanos = Math.max(0L, System.nanoTime() - state.startedAtNanos);
            boolean sampled = state.observationWeight > RequestState.SLOW_ONLY;
            if (sampled || status >= 500 || durationNanos >= slowThresholdNanos) {
                record(request, status, durationNanos, sampled);
            }
        } finally {
            state.clearObservation();
        }
    }

    private void completeAsync(HttpServletRequest request, int status) {
        Observation observation = (Observation) request.getAttribute(OBSERVATION_ATTRIBUTE);
        if (observation == null) {
            if (status >= 500) record(request, status, 0L, false);
            return;
        }
        request.removeAttribute(OBSERVATION_ATTRIBUTE);
        long durationNanos = Math.max(0L, System.nanoTime() - observation.startedAtNanos);
        boolean sampled = observation.observationWeight > RequestState.SLOW_ONLY;
        if (sampled || status >= 500 || durationNanos >= slowThresholdNanos) {
            record(request, status, durationNanos, sampled);
        }
    }

    private void record(
            HttpServletRequest request,
            int status,
            long durationNanos,
            boolean sampled) {
        String method = request.getMethod();
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matched == null ? UNMATCHED_ROUTE : matched.toString();
        int slot = routeSlot(method, route);
        if (slot != DISABLED_SLOT) {
            telemetry.recordHttp(slot, status, durationNanos, sampled ? sampleRate : 0);
        }
    }

    private int routeSlot(String method, String route) {
        String key = method + ' ' + route;
        synchronized (routeSlots) {
            Integer existing = routeSlots.get(key);
            if (existing != null) return existing;
            if (routeSlots.size() >= maxRoutes) return DISABLED_SLOT;
            int slot = telemetry.registerHttpRoute(method, route);
            if (slot != DISABLED_SLOT) routeSlots.put(key, slot);
            return slot;
        }
    }

    private static int initialSampleOffset(long threadId, int mask) {
        long mixed = threadId * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        return (int) mixed & mask;
    }

    private static final class RequestState {
        private static final int NOT_OBSERVING = 0;
        private static final int SLOW_ONLY = -1;

        private int remaining;
        private int observationWeight;
        private long startedAtNanos;

        private RequestState(int initialOffset) {
            this.remaining = initialOffset;
        }

        private boolean next(int mask) {
            if (remaining == 0) {
                remaining = mask;
                return true;
            }
            remaining--;
            return false;
        }

        private void begin(int observationWeight) {
            this.observationWeight = observationWeight;
            startedAtNanos = System.nanoTime();
        }

        private void clearObservation() {
            observationWeight = NOT_OBSERVING;
            startedAtNanos = 0L;
        }
    }

    private static final class Observation {
        private final int observationWeight;
        private final long startedAtNanos;

        private Observation(int observationWeight, long startedAtNanos) {
            this.observationWeight = observationWeight;
            this.startedAtNanos = startedAtNanos;
        }
    }
}
