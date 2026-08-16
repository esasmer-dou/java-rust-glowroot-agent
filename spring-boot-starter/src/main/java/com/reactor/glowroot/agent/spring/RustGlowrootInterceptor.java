package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Low-allocation Spring MVC request timing adapter. */
public final class RustGlowrootInterceptor implements AsyncHandlerInterceptor {

    private static final int DISABLED_SLOT = HttpRouteRegistry.DISABLED_SLOT;
    private static final int TIMED_OBSERVATION = 1;
    private static final int SAMPLED_OBSERVATION = 1 << 1;
    private static final String UNMATCHED_ROUTE = "<unmatched>";
    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";
    private static final Object UNSAMPLED_ASYNC = new Object();

    private final HttpTelemetryRecorder telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final boolean slowTraceEnabled;
    private final long slowThresholdNanos;
    private final ThreadLocal<RequestState> requestState;
    private final HttpRouteRegistry routeSlots;

    /**
     * Creates the low-allocation MVC interceptor.
     *
     * @param telemetry process telemetry handle
     * @param config bounded telemetry configuration
     */
    public RustGlowrootInterceptor(NativeTelemetry telemetry, TelemetryConfig config) {
        this(new NativeHttpTelemetryRecorder(telemetry), config);
    }

    RustGlowrootInterceptor(HttpTelemetryRecorder telemetry, TelemetryConfig config) {
        this.telemetry = telemetry;
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1;
        this.slowTraceEnabled = config.traceCapacity() != 0;
        this.slowThresholdNanos = !slowTraceEnabled
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.requestState = ThreadLocal.withInitial(() ->
                new RequestState(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new HttpRouteRegistry(config.maxRoutes());
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;

        requestState.get().begin(sampleMask, slowTraceEnabled);
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        RequestState state = requestState.get();
        request.setAttribute(OBSERVATION_ATTRIBUTE, state.asyncObservation());
        state.clearObservation();
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        complete(request, response, exception, 0);
    }

    void complete(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception,
            int forcedStatus) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            completeAsync(request, response, exception, forcedStatus);
            return;
        }

        RequestState state = requestState.get();
        int observationFlags = state.takeObservationFlags();
        int status = forcedStatus == 0 ? response.getStatus() : forcedStatus;
        if (exception != null && status < 500) status = 500;

        // Successful unsampled requests are the dominant path. Return before reading the timer,
        // resolving route metadata, or touching native state.
        if (observationFlags == 0) {
            if (status >= 500) recordStatus(request, status, 0L, false, exception);
            return;
        }

        boolean sampled = (observationFlags & SAMPLED_OBSERVATION) != 0;
        long durationNanos = Math.max(0L, System.nanoTime() - state.startedAtNanos());
        if (sampled || status >= 500 || durationNanos >= slowThresholdNanos) {
            recordStatus(request, status, durationNanos, sampled, exception);
        }
    }

    void completeAsync(
            HttpServletRequest request,
            HttpServletResponse response,
            Exception exception,
            int forcedStatus) {
        Object observed = request.getAttribute(OBSERVATION_ATTRIBUTE);
        if (observed == null) return;
        request.removeAttribute(OBSERVATION_ATTRIBUTE);

        int status = forcedStatus == 0 ? response.getStatus() : forcedStatus;
        if (exception != null && status < 500) status = 500;
        if (observed instanceof Observation observation) {
            long durationNanos = Math.max(0L, System.nanoTime() - observation.startedAtNanos());
            if (observation.sampled() || status >= 500 || durationNanos >= slowThresholdNanos) {
                recordStatus(request, status, durationNanos, observation.sampled(), exception);
            }
            return;
        }
        if (status >= 500) recordStatus(request, status, 0L, false, exception);
    }

    private void recordStatus(
            HttpServletRequest request,
            int status,
            long durationNanos,
            boolean sampled,
            Throwable error) {
        String method = request.getMethod();
        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matched == null ? UNMATCHED_ROUTE : matched.toString();
        int slot = routeSlot(method, route);
        if (slot != DISABLED_SLOT) {
            // Errors are exact events, never statistically weighted successes.
            int sampleWeight = sampled && status < 500 ? sampleRate : 0;
            telemetry.recordHttp(slot, status, durationNanos, sampleWeight);
            if (error != null) telemetry.recordError(slot, durationNanos, error);
        }
    }

    private int routeSlot(String method, String route) {
        return routeSlots.getOrRegister(method, route, telemetry);
    }

    private static int initialSampleOffset(long threadId, int mask) {
        long mixed = threadId * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        return (int) mixed & mask;
    }

    private record Observation(boolean sampled, long startedAtNanos) {}

    private static final class RequestState {
        private int remaining;
        private int observationFlags;
        private long startedAtNanos;

        private RequestState(int initialOffset) {
            this.remaining = initialOffset;
        }

        private void begin(int mask, boolean slowTraceEnabled) {
            boolean sampled = next(mask);
            if (sampled || slowTraceEnabled) {
                startedAtNanos = System.nanoTime();
                observationFlags = TIMED_OBSERVATION
                        | (sampled ? SAMPLED_OBSERVATION : 0);
                return;
            }
            if (observationFlags != 0) observationFlags = 0;
        }

        private Object asyncObservation() {
            if ((observationFlags & TIMED_OBSERVATION) == 0) return UNSAMPLED_ASYNC;
            return new Observation(
                    (observationFlags & SAMPLED_OBSERVATION) != 0,
                    startedAtNanos
            );
        }

        private long startedAtNanos() {
            return startedAtNanos;
        }

        private void clearObservation() {
            observationFlags = 0;
        }

        private int takeObservationFlags() {
            int flags = observationFlags;
            if (flags != 0) observationFlags = 0;
            return flags;
        }

        private boolean next(int mask) {
            if (remaining == 0) {
                remaining = mask;
                return true;
            }
            remaining--;
            return false;
        }
    }
}
