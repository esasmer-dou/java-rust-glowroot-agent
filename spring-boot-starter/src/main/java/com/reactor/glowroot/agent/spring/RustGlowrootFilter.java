package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Low-allocation Spring MVC request timing adapter for the Rust telemetry runtime. */
public final class RustGlowrootFilter implements Filter {

    private static final int DISABLED_SLOT = -1;
    private static final String UNMATCHED_ROUTE = "<unmatched>";

    private final HttpTelemetryRecorder telemetry;
    private final int sampleRate;
    private final long sampleMask;
    private final int sampleShift;
    private final long slowThresholdNanos;
    private final int maxRoutes;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, Integer> routeSlots;

    /**
     * Creates the low-allocation servlet filter.
     *
     * @param telemetry process telemetry handle
     * @param config bounded telemetry configuration
     */
    public RustGlowrootFilter(NativeTelemetry telemetry, TelemetryConfig config) {
        this(new NativeRecorder(telemetry), config);
    }

    RustGlowrootFilter(HttpTelemetryRecorder telemetry, TelemetryConfig config) {
        this.telemetry = telemetry;
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1L;
        this.sampleShift = Integer.numberOfTrailingZeros(sampleRate);
        this.slowThresholdNanos = config.traceCapacity() == 0
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.maxRoutes = config.maxRoutes();
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
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        long sequence = requestSequence.getAndIncrement();
        boolean sampled = sampled(sequence);
        long startedAt = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            long durationNanos = Math.max(0L, System.nanoTime() - startedAt);
            int failureStatus = failure == null ? 0 : 500;
            if (failure == null && httpRequest.isAsyncStarted()) {
                recordAsync(httpRequest, httpResponse, sampled, startedAt);
            } else {
                record(httpRequest, httpResponse, sampled, durationNanos, failureStatus);
            }
        }
    }

    private boolean sampled(long sequence) {
        long block = sequence >>> sampleShift;
        long selectedPosition = (block ^ (block >>> sampleShift)) & sampleMask;
        return (sequence & sampleMask) == selectedPosition;
    }

    private void recordAsync(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean sampled,
            long startedAt) {
        AsyncCompletion listener = new AsyncCompletion(request, sampled, startedAt);
        try {
            request.getAsyncContext().addListener(listener);
        } catch (IllegalStateException completedBeforeRegistration) {
            record(request, response, sampled, System.nanoTime() - startedAt, 0);
        }
    }

    private void record(
            HttpServletRequest request,
            HttpServletResponse response,
            boolean sampled,
            long durationNanos,
            int forcedStatus) {
        int status = forcedStatus > 0 ? forcedStatus : response.getStatus();
        if (!sampled && status < 500 && durationNanos < slowThresholdNanos) return;

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

    private final class AsyncCompletion implements AsyncListener {
        private final HttpServletRequest request;
        private final boolean sampled;
        private final long startedAt;
        private final AtomicBoolean completed = new AtomicBoolean();

        private AsyncCompletion(HttpServletRequest request, boolean sampled, long startedAt) {
            this.request = request;
            this.sampled = sampled;
            this.startedAt = startedAt;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(event, 0);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            complete(event, 504);
        }

        @Override
        public void onError(AsyncEvent event) {
            complete(event, 500);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void complete(AsyncEvent event, int forcedStatus) {
            if (!completed.compareAndSet(false, true)) return;
            if (event.getSuppliedResponse() instanceof HttpServletResponse response) {
                record(request, response, sampled, System.nanoTime() - startedAt, forcedStatus);
            }
        }
    }
}
