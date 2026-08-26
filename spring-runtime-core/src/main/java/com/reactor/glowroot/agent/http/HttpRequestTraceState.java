package com.reactor.glowroot.agent.http;

/**
 * Diagnostic-only request correlation for Spring controller and Dubbo consumer timing.
 * The state is request-owned; no request, response, argument, or result object is retained.
 */
public final class HttpRequestTraceState {

    private static final long UNAVAILABLE = -1L;
    private static volatile boolean enabled;
    private static volatile CurrentLookup currentLookup;

    private long controllerStartedAtNanos = UNAVAILABLE;
    private long controllerCompletedAtNanos = UNAVAILABLE;
    private long firstDubboStartedAtNanos = UNAVAILABLE;
    private long dubboDurationNanos;
    private long dubboCount;
    private long dubboErrors;
    private String dubboService;
    private String dubboMethod;
    private volatile boolean acceptingDubbo = true;

    private HttpRequestTraceState() {}

    /** Enables or disables diagnostic request correlation process-wide. */
    public static void setEnabled(boolean required) {
        enabled = required;
    }

    /** Installs the web adapter lookup without making the runtime core depend on Spring. */
    public static void setCurrentLookup(CurrentLookup lookup) {
        currentLookup = lookup;
    }

    /** Opens one request state only while the diagnostic profile is active. */
    public static HttpRequestTraceState open() {
        if (!enabled) return null;
        return new HttpRequestTraceState();
    }

    /** Returns the request state visible to a synchronous downstream client call. */
    public static HttpRequestTraceState current() {
        CurrentLookup lookup = currentLookup;
        if (!enabled || lookup == null) return null;
        HttpRequestTraceState state = lookup.current();
        return state != null && state.acceptingDubbo ? state : null;
    }

    /** Starts controller timing without allocating another observation object. */
    public void beginController() {
        if (controllerStartedAtNanos == UNAVAILABLE) {
            controllerStartedAtNanos = System.nanoTime();
        }
    }

    /** Completes controller timing and releases the request thread reference. */
    public void completeController() {
        if (controllerStartedAtNanos != UNAVAILABLE
                && controllerCompletedAtNanos == UNAVAILABLE) {
            controllerCompletedAtNanos = System.nanoTime();
        }
        acceptingDubbo = false;
    }

    /** Starts one bounded Dubbo consumer observation, or returns {@code null} when disabled. */
    public static DubboObservation beginDubbo(String service, String method) {
        HttpRequestTraceState state = current();
        if (state == null) return null;
        long startedAtNanos = System.nanoTime();
        if (!state.registerDubboStart(startedAtNanos, service, method)) return null;
        return new DubboObservation(state, startedAtNanos);
    }

    private synchronized boolean registerDubboStart(
            long startedAtNanos,
            String service,
            String method) {
        if (!acceptingDubbo) return false;
        if (firstDubboStartedAtNanos == UNAVAILABLE) {
            firstDubboStartedAtNanos = startedAtNanos;
            dubboService = service;
            dubboMethod = method;
        }
        return true;
    }

    private synchronized void completeDubbo(long startedAtNanos, boolean error) {
        dubboDurationNanos = saturatingAdd(
                dubboDurationNanos,
                Math.max(0L, System.nanoTime() - startedAtNanos));
        dubboCount = saturatingAdd(dubboCount, 1L);
        if (error) dubboErrors = saturatingAdd(dubboErrors, 1L);
    }

    /** Creates one immutable snapshot only after the request was selected for trace export. */
    public synchronized Snapshot snapshot(long requestDurationNanos) {
        long now = System.nanoTime();
        long requestStartedAtNanos = now - Math.max(0L, requestDurationNanos);
        long controllerEnd = controllerCompletedAtNanos == UNAVAILABLE
                ? now : controllerCompletedAtNanos;
        long controllerDuration = controllerStartedAtNanos == UNAVAILABLE
                ? UNAVAILABLE
                : Math.max(0L, controllerEnd - controllerStartedAtNanos);
        return new Snapshot(
                offset(requestStartedAtNanos, controllerStartedAtNanos),
                controllerDuration,
                operation(dubboService, dubboMethod),
                offset(requestStartedAtNanos, firstDubboStartedAtNanos),
                dubboDurationNanos,
                dubboCount,
                dubboErrors);
    }

    private static long offset(long requestStartedAtNanos, long startedAtNanos) {
        return startedAtNanos == UNAVAILABLE
                ? UNAVAILABLE
                : Math.max(0L, startedAtNanos - requestStartedAtNanos);
    }

    private static String operation(String service, String method) {
        if (service == null || service.isBlank()) return method == null ? "" : method;
        if (method == null || method.isBlank()) return service;
        return service + "#" + method + "()";
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /** Immutable values passed to Rust only for a sampled, slow, or failed request. */
    public record Snapshot(
            long controllerStartOffsetNanos,
            long controllerDurationNanos,
            String dubboOperation,
            long dubboStartOffsetNanos,
            long dubboDurationNanos,
            long dubboCount,
            long dubboErrors) {}

    /** Adapter-owned lookup for the request state already held by the web container. */
    @FunctionalInterface
    public interface CurrentLookup {
        HttpRequestTraceState current();
    }

    /** Per-call marker stored on Dubbo's invocation context until its listener completes. */
    public static final class DubboObservation {
        private final HttpRequestTraceState owner;
        private final long startedAtNanos;
        private boolean completed;

        private DubboObservation(HttpRequestTraceState owner, long startedAtNanos) {
            this.owner = owner;
            this.startedAtNanos = startedAtNanos;
        }

        public synchronized void complete(boolean error) {
            if (completed) return;
            completed = true;
            owner.completeDubbo(startedAtNanos, error);
        }
    }
}
