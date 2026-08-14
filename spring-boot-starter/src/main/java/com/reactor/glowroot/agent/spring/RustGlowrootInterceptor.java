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

    private static final int DISABLED_SLOT = -1;
    private static final String UNMATCHED_ROUTE = "<unmatched>";
    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";
    private static final Object UNSAMPLED_ASYNC = new Object();

    private final HttpTelemetryRecorder telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final boolean slowTraceEnabled;
    private final long slowThresholdNanos;
    private final ThreadLocal<SampleCursor> sampleCursor;
    private final RouteSlotTable routeSlots;

    /**
     * Creates the low-allocation MVC interceptor.
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
        this.sampleCursor = ThreadLocal.withInitial(() ->
                new SampleCursor(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new RouteSlotTable(config.maxRoutes());
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

        @Override
        public void recordError(int slot, long durationNanos, Throwable error) {
            telemetry.recordError(slot, durationNanos, error);
        }
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) return true;

        boolean sampled = sampleCursor.get().next(sampleMask);
        if (sampled || slowTraceEnabled) {
            request.setAttribute(
                    OBSERVATION_ATTRIBUTE,
                    new Observation(sampled, System.nanoTime())
            );
        }
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        if (request.getAttribute(OBSERVATION_ATTRIBUTE) == null) {
            request.setAttribute(OBSERVATION_ATTRIBUTE, UNSAMPLED_ASYNC);
        }
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
        Object observed = request.getAttribute(OBSERVATION_ATTRIBUTE);
        request.removeAttribute(OBSERVATION_ATTRIBUTE);
        if (observed == null && request.getDispatcherType() != DispatcherType.REQUEST) return;

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

    /** Fixed-capacity route table; lookups do not allocate temporary composite keys. */
    private static final class RouteSlotTable {
        private final int maxRoutes;
        private final int mask;
        private final String[] methods;
        private final String[] routes;
        private final int[] slots;
        private int size;

        private RouteSlotTable(int maxRoutes) {
            int capacity = 2;
            while (capacity < maxRoutes * 2) capacity <<= 1;
            this.maxRoutes = maxRoutes;
            this.mask = capacity - 1;
            this.methods = new String[capacity];
            this.routes = new String[capacity];
            this.slots = new int[capacity];
        }

        private synchronized int getOrRegister(
                String method,
                String route,
                HttpTelemetryRecorder telemetry) {
            int index = spreadHash(method, route) & mask;
            while (methods[index] != null) {
                if (methods[index].equals(method) && routes[index].equals(route)) {
                    return slots[index];
                }
                index = (index + 1) & mask;
            }
            if (size >= maxRoutes) return DISABLED_SLOT;

            int slot = telemetry.registerHttpRoute(method, route);
            if (slot == DISABLED_SLOT) return DISABLED_SLOT;
            methods[index] = method;
            routes[index] = route;
            slots[index] = slot;
            size++;
            return slot;
        }

        private static int spreadHash(String method, String route) {
            int hash = 31 * method.hashCode() + route.hashCode();
            return hash ^ (hash >>> 16);
        }
    }

    private static final class SampleCursor {
        private int remaining;

        private SampleCursor(int initialOffset) {
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
    }
}
