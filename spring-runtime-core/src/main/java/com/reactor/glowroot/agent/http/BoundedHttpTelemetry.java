package com.reactor.glowroot.agent.http;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Shared allocation-bounded HTTP aggregate, trace sampler, and native route registry. */
public final class BoundedHttpTelemetry {

    public static final String UNMATCHED_ROUTE = "<unmatched>";
    public static final String ROUTE_LIMIT_EXCEEDED = "<route-limit-exceeded>";

    private static final int DISABLED_SLOT = -1;
    private static final int SAMPLED = 1;
    private static final int CHECK_SLOW = 1 << 1;
    private static final int FAILED = 1 << 2;
    private static final int EXACT_AGGREGATE = 1 << 3;
    private static final int TOKEN_FLAG_SHIFT = 60;
    private static final long TOKEN_TIME_MASK = (1L << TOKEN_FLAG_SHIFT) - 1L;

    private final HttpTelemetrySink telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final boolean traceSamplingEnabled;
    private final boolean errorDetailsEnabled;
    private final long slowThresholdNanos;
    private final ThreadLocal<SampleState> sampleState;
    private final RouteRegistry routeSlots;

    public BoundedHttpTelemetry(HttpTelemetrySink telemetry, TelemetryConfig config) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1;
        this.traceSamplingEnabled = config.traceCapacity() > 0;
        this.errorDetailsEnabled = config.profile().errorDetailsEnabled()
                && config.errorTraceCapacity() > 0;
        this.slowThresholdNanos = config.traceCapacity() == 0
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.sampleState = ThreadLocal.withInitial(() ->
                new SampleState(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new RouteRegistry(config.maxRoutes());
    }

    /** Starts exact aggregate observation and marks an optional trace sample. */
    public int observation(int status) {
        // Direct server adapters already own request timing. Avoid ThreadLocal sampler state in the
        // default aggregate-only profile; exact aggregation itself needs no Java request state.
        int observation = traceSamplingEnabled
                ? nextObservation(sampleState.get())
                : EXACT_AGGREGATE;
        if (status >= 500) observation |= FAILED;
        return observation;
    }

    /** Starts lifecycle observation without allocating a request object. */
    public int beginObservation() {
        return traceSamplingEnabled
                ? nextObservation(sampleState.get())
                : EXACT_AGGREGATE;
    }

    /** Starts one thread-bound MVC observation without allocating request state. */
    public void beginThreadObservation() {
        SampleState state = sampleState.get();
        int flags = nextObservation(state);
        state.activeToken = ((long) flags << TOKEN_FLAG_SHIFT)
                | (System.nanoTime() & TOKEN_TIME_MASK);
    }

    /** Detaches and clears the current thread-bound observation for sync or async completion. */
    public long takeThreadObservation() {
        SampleState state = sampleState.get();
        long token = state.activeToken;
        state.activeToken = 0L;
        return token;
    }

    /** Returns the sampler flags stored in a detached primitive observation token. */
    public int observationFlags(long token) {
        return (int) (token >>> TOKEN_FLAG_SHIFT);
    }

    /** Computes elapsed time from a detached token while tolerating the nano clock wrap boundary. */
    public long elapsedNanos(long token) {
        if (token == 0L) return 0L;
        return (System.nanoTime() - (token & TOKEN_TIME_MASK)) & TOKEN_TIME_MASK;
    }

    private int nextObservation(SampleState state) {
        boolean sampled = traceSamplingEnabled && state.next(sampleMask);
        int observation = EXACT_AGGREGATE | (sampled ? SAMPLED : 0);
        if (slowThresholdNanos != Long.MAX_VALUE) observation |= CHECK_SLOW;
        return observation;
    }

    /** Records one already completed request without retaining request or response objects. */
    public void record(
            int observation,
            String method,
            Object route,
            int status,
            long durationNanos,
            Throwable error) {
        record(observation, method, route, status, durationNanos, error, null);
    }

    /** Records a completion and adds bounded trace metadata only for selected traces. */
    public void record(
            int observation,
            String method,
            Object route,
            int status,
            long durationNanos,
            Throwable error,
            Object controller) {
        recordInternal(
                observation,
                method,
                route,
                status,
                durationNanos,
                error,
                controller,
                null,
                null);
    }

    /** Records a completion with diagnostic-only request-thread deltas. */
    public void record(
            int observation,
            String method,
            Object route,
            int status,
            long durationNanos,
            Throwable error,
            Object controller,
            long[] threadStats) {
        record(
                observation,
                method,
                route,
                status,
                durationNanos,
                error,
                controller,
                threadStats,
                null);
    }

    /** Records diagnostic-only controller and Dubbo correlation for a selected trace. */
    public void record(
            int observation,
            String method,
            Object route,
            int status,
            long durationNanos,
            Throwable error,
            Object controller,
            long[] threadStats,
            HttpRequestTraceState requestTraceState) {
        recordInternal(
                observation,
                method,
                route,
                status,
                durationNanos,
                error,
                controller,
                threadStats,
                requestTraceState);
    }

    /** Records a completed request using Glowroot-compatible route-only transaction naming. */
    public void record(
            int observation,
            Object route,
            int status,
            long durationNanos,
            Throwable error) {
        recordInternal(observation, "", route, status, durationNanos, error, null, null, null);
    }

    private void recordInternal(
            int observation,
            String method,
            Object route,
            int status,
            long durationNanos,
            Throwable error,
            Object controller,
            long[] threadStats,
            HttpRequestTraceState requestTraceState) {
        long boundedDuration = Math.max(0L, durationNanos);
        if (!shouldRecord(observation, status, boundedDuration, error)) return;

        int normalizedStatus = error != null && status < 500 ? 500 : status;
        boolean sampled = (observation & SAMPLED) != 0;
        boolean failed = (observation & FAILED) != 0 || normalizedStatus >= 500 || error != null;

        String routeValue = route == null
                ? UNMATCHED_ROUTE
                : route instanceof String text ? text : route.toString();
        try {
            String methodValue = method == null ? "" : method;
            int slot = routeSlots.getOrRegister(routeValue, telemetry);
            if (slot == DISABLED_SLOT) return;
            int sampleWeight = sampled && !failed ? sampleRate : 0;
            if (shouldRecordDetail(observation, normalizedStatus, boundedDuration, error)) {
                HttpRequestTraceState.Snapshot requestTrace = requestTraceState == null
                        ? null : requestTraceState.snapshot(boundedDuration);
                telemetry.recordHttpDetail(
                        slot,
                        normalizedStatus,
                        boundedDuration,
                        sampleWeight,
                        methodValue,
                        controller == null ? "" : controller.toString(),
                        stat(threadStats, 0),
                        stat(threadStats, 1),
                        stat(threadStats, 2),
                        stat(threadStats, 3),
                        requestTrace == null ? -1L : requestTrace.controllerStartOffsetNanos(),
                        requestTrace == null ? -1L : requestTrace.controllerDurationNanos(),
                        requestTrace == null ? "" : requestTrace.dubboOperation(),
                        requestTrace == null ? -1L : requestTrace.dubboStartOffsetNanos(),
                        requestTrace == null ? 0L : requestTrace.dubboDurationNanos(),
                        requestTrace == null ? 0L : requestTrace.dubboCount(),
                        requestTrace == null ? 0L : requestTrace.dubboErrors(),
                        error);
            } else {
                telemetry.recordHttp(slot, normalizedStatus, boundedDuration, sampleWeight);
                if (error != null) telemetry.recordError(slot, boundedDuration, error);
            }
        } catch (RuntimeException ignored) {
            // Telemetry must never alter the application response during shutdown or native failure.
        }
    }

    private static long stat(long[] values, int index) {
        return values == null || values.length <= index ? -1L : values[index];
    }

    /** Returns whether this completion contributes to exact aggregate or error telemetry. */
    public boolean shouldRecord(
            int observation,
            int status,
            long durationNanos,
            Throwable error) {
        return (observation & EXACT_AGGREGATE) != 0
                || (observation & FAILED) != 0
                || status >= 500
                || error != null;
    }

    /** Returns whether completion metadata and thread deltas are needed for this request. */
    public boolean shouldRecordDetail(
            int observation,
            int status,
            long durationNanos,
            Throwable error) {
        boolean selected = (observation & SAMPLED) != 0
                || (observation & FAILED) != 0
                || status >= 500
                || error != null
                || ((observation & CHECK_SLOW) != 0 && durationNanos >= slowThresholdNanos);
        return selected && (traceSamplingEnabled || errorDetailsEnabled);
    }

    private static int initialSampleOffset(long threadId, int mask) {
        long mixed = threadId * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        return (int) mixed & mask;
    }

    private static final class SampleState {
        private int remaining;
        private long activeToken;

        private SampleState(int initialOffset) {
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

    private static final class RouteRegistry {
        private static final VarHandle STRING_ELEMENT =
                MethodHandles.arrayElementVarHandle(String[].class);

        private final int maxNamedRoutes;
        private final int mask;
        private final String[] routes;
        private final int[] slots;
        private volatile int size;
        private volatile int overflowSlot = DISABLED_SLOT;
        private volatile boolean overflowRegistrationAttempted;

        private RouteRegistry(int maxRoutes) {
            int capacity = 2;
            while (capacity < maxRoutes * 2) capacity <<= 1;
            // Reserve one bounded slot for overflow so route-cardinality pressure never makes
            // completed requests disappear silently from Glowroot transaction aggregates.
            this.maxNamedRoutes = maxRoutes - 1;
            this.mask = capacity - 1;
            this.routes = new String[capacity];
            this.slots = new int[capacity];
        }

        private int getOrRegister(String route, HttpTelemetrySink telemetry) {
            int registered = find(route);
            if (registered != DISABLED_SLOT) return registered;
            if (size >= maxNamedRoutes) return overflowSlot(telemetry);
            return register(route, telemetry);
        }

        private int find(String route) {
            int index = spreadHash(route) & mask;
            String registeredRoute;
            while ((registeredRoute = (String) STRING_ELEMENT.getAcquire(routes, index)) != null) {
                if (registeredRoute.equals(route)) {
                    return slots[index];
                }
                index = (index + 1) & mask;
            }
            return DISABLED_SLOT;
        }

        private synchronized int register(String route, HttpTelemetrySink telemetry) {
            int registered = find(route);
            if (registered != DISABLED_SLOT) return registered;
            if (size >= maxNamedRoutes) return registerOverflow(telemetry);

            int index = spreadHash(route) & mask;
            while ((String) STRING_ELEMENT.getAcquire(routes, index) != null) {
                index = (index + 1) & mask;
            }
            int slot = telemetry.registerHttpRoute("", route);
            if (slot == DISABLED_SLOT) return DISABLED_SLOT;
            slots[index] = slot;
            STRING_ELEMENT.setRelease(routes, index, route);
            size++;
            return slot;
        }

        private int overflowSlot(HttpTelemetrySink telemetry) {
            int registered = overflowSlot;
            if (registered != DISABLED_SLOT || overflowRegistrationAttempted) return registered;
            synchronized (this) {
                return registerOverflow(telemetry);
            }
        }

        private int registerOverflow(HttpTelemetrySink telemetry) {
            if (overflowSlot != DISABLED_SLOT || overflowRegistrationAttempted) return overflowSlot;
            int registered = telemetry.registerHttpRoute("", ROUTE_LIMIT_EXCEEDED);
            overflowSlot = registered;
            // A native exception is handled by the outer fail-open boundary and is retried on the
            // next request. A normal disabled result is stable and must not call JNI forever.
            overflowRegistrationAttempted = true;
            return overflowSlot;
        }

        private static int spreadHash(String route) {
            int hash = route.hashCode();
            return hash ^ (hash >>> 16);
        }
    }
}
