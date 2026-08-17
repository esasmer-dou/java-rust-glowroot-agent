package com.reactor.glowroot.agent.http;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Shared allocation-bounded HTTP sampler and native route registry. */
public final class BoundedHttpTelemetry {

    public static final String UNMATCHED_ROUTE = "<unmatched>";

    private static final int DISABLED_SLOT = -1;
    private static final int SAMPLED = 1;
    private static final int CHECK_SLOW = 1 << 1;
    private static final int FAILED = 1 << 2;
    private static final int TOKEN_FLAG_SHIFT = 61;
    private static final long TOKEN_TIME_MASK = (1L << TOKEN_FLAG_SHIFT) - 1L;

    private final HttpTelemetrySink telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final long slowThresholdNanos;
    private final ThreadLocal<SampleState> sampleState;
    private final RouteRegistry routeSlots;

    public BoundedHttpTelemetry(HttpTelemetrySink telemetry, TelemetryConfig config) {
        this.telemetry = java.util.Objects.requireNonNull(telemetry, "telemetry");
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1;
        this.slowThresholdNanos = config.traceCapacity() == 0
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.sampleState = ThreadLocal.withInitial(() ->
                new SampleState(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new RouteRegistry(config.maxRoutes());
    }

    /**
     * Decides whether completion data may be needed for this response.
     *
     * <p>The dominant unsampled successful path returns zero. Callers should then avoid clocks,
     * route lookup, and JNI.</p>
     */
    public int observation(int status) {
        int observation = beginObservation();
        if (status >= 500) observation |= FAILED;
        return observation;
    }

    /** Starts lifecycle observation without allocating a request object. */
    public int beginObservation() {
        return nextObservation(sampleState.get());
    }

    /** Starts one thread-bound MVC observation without allocating request state. */
    public void beginThreadObservation() {
        SampleState state = sampleState.get();
        int flags = nextObservation(state);
        state.activeToken = flags == 0
                ? 0L
                : ((long) flags << TOKEN_FLAG_SHIFT) | (System.nanoTime() & TOKEN_TIME_MASK);
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
        boolean sampled = state.next(sampleMask);
        int observation = sampled ? SAMPLED : 0;
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
        long boundedDuration = Math.max(0L, durationNanos);
        if (!shouldRecord(observation, status, boundedDuration, error)) return;

        int normalizedStatus = error != null && status < 500 ? 500 : status;
        boolean sampled = (observation & SAMPLED) != 0;
        boolean failed = (observation & FAILED) != 0 || normalizedStatus >= 500 || error != null;

        String routeValue = route == null
                ? UNMATCHED_ROUTE
                : route instanceof String text ? text : route.toString();
        try {
            int slot = routeSlots.getOrRegister(method, routeValue, telemetry);
            if (slot == DISABLED_SLOT) return;
            telemetry.recordHttp(
                    slot,
                    normalizedStatus,
                    boundedDuration,
                    sampled && !failed ? sampleRate : 0);
            if (error != null) telemetry.recordError(slot, boundedDuration, error);
        } catch (RuntimeException ignored) {
            // Telemetry must never alter the application response during shutdown or native failure.
        }
    }

    /** Lets reactive adapters skip route and method lookup on the dominant successful path. */
    public boolean shouldRecord(
            int observation,
            int status,
            long durationNanos,
            Throwable error) {
        boolean failed = (observation & FAILED) != 0 || status >= 500 || error != null;
        if (observation == 0) return failed;
        return (observation & SAMPLED) != 0
                || failed
                || Math.max(0L, durationNanos) >= slowThresholdNanos;
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

        private final int maxRoutes;
        private final int mask;
        private final String[] methods;
        private final String[] routes;
        private final int[] slots;
        private int size;

        private RouteRegistry(int maxRoutes) {
            int capacity = 2;
            while (capacity < maxRoutes * 2) capacity <<= 1;
            this.maxRoutes = maxRoutes;
            this.mask = capacity - 1;
            this.methods = new String[capacity];
            this.routes = new String[capacity];
            this.slots = new int[capacity];
        }

        private int getOrRegister(String method, String route, HttpTelemetrySink telemetry) {
            int registered = find(method, route);
            if (registered != DISABLED_SLOT) return registered;
            return register(method, route, telemetry);
        }

        private int find(String method, String route) {
            int index = spreadHash(method, route) & mask;
            String registeredMethod;
            while ((registeredMethod = (String) STRING_ELEMENT.getAcquire(methods, index)) != null) {
                if (registeredMethod.equals(method) && routes[index].equals(route)) {
                    return slots[index];
                }
                index = (index + 1) & mask;
            }
            return DISABLED_SLOT;
        }

        private synchronized int register(
                String method,
                String route,
                HttpTelemetrySink telemetry) {
            int registered = find(method, route);
            if (registered != DISABLED_SLOT) return registered;
            if (size >= maxRoutes) return DISABLED_SLOT;

            int index = spreadHash(method, route) & mask;
            while ((String) STRING_ELEMENT.getAcquire(methods, index) != null) {
                index = (index + 1) & mask;
            }
            int slot = telemetry.registerHttpRoute(method, route);
            if (slot == DISABLED_SLOT) return DISABLED_SLOT;
            routes[index] = route;
            slots[index] = slot;
            STRING_ELEMENT.setRelease(methods, index, method);
            size++;
            return slot;
        }

        private static int spreadHash(String method, String route) {
            int hash = 31 * method.hashCode() + route.hashCode();
            return hash ^ (hash >>> 16);
        }
    }
}
