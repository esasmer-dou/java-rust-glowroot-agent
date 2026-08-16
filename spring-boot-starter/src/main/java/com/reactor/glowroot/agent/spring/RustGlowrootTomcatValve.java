package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tomcat-native HTTP edge that avoids the per-request Spring interceptor lifecycle. */
final class RustGlowrootTomcatValve extends ValveBase {

    private static final String UNMATCHED_ROUTE = "<unmatched>";

    private final HttpTelemetryRecorder telemetry;
    private final int sampleRate;
    private final int sampleMask;
    private final boolean slowTraceEnabled;
    private final long slowThresholdNanos;
    private final ThreadLocal<SampleState> sampleState;
    private final HttpRouteRegistry routeSlots;

    RustGlowrootTomcatValve(NativeTelemetry telemetry, TelemetryConfig config) {
        this(new NativeHttpTelemetryRecorder(telemetry), config);
    }

    RustGlowrootTomcatValve(HttpTelemetryRecorder telemetry, TelemetryConfig config) {
        super(true);
        this.telemetry = telemetry;
        this.sampleRate = config.httpSampleRate();
        this.sampleMask = sampleRate - 1;
        this.slowTraceEnabled = config.traceCapacity() != 0;
        this.slowThresholdNanos = !slowTraceEnabled
                ? Long.MAX_VALUE
                : config.slowThresholdMs() * 1_000_000L;
        this.sampleState = ThreadLocal.withInitial(() ->
                new SampleState(initialSampleOffset(Thread.currentThread().threadId(), sampleMask)));
        this.routeSlots = new HttpRouteRegistry(config.maxRoutes());
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        DispatcherType dispatcherType = request.getDispatcherType();
        if (dispatcherType != null && dispatcherType != DispatcherType.REQUEST) {
            getNext().invoke(request, response);
            return;
        }

        try {
            getNext().invoke(request, response);
        } catch (IOException | ServletException | RuntimeException error) {
            complete(request, response, error, 500);
            throw error;
        } catch (Error error) {
            complete(request, response, error, 500);
            throw error;
        }

        if (!request.isAsyncStarted()) {
            complete(request, response, null, 0);
            return;
        }

        AsyncCompletion completion = new AsyncCompletion(request, response);
        try {
            request.getAsyncContext().addListener(completion);
        } catch (IllegalStateException completedBeforeRegistration) {
            completion.complete(null, 0);
        }
    }

    private void complete(Request request, Response response, Throwable error, int forcedStatus) {
        int status = forcedStatus == 0 ? response.getStatus() : forcedStatus;
        if (error != null && status < 500) status = 500;
        boolean sampled = sampleState.get().next(sampleMask);
        boolean failed = status >= 500;
        if (!sampled && !failed && !slowTraceEnabled) return;

        long startedAtNanos = request.getCoyoteRequest().getStartTimeNanos();
        long durationNanos = startedAtNanos <= 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - startedAtNanos);
        if (!sampled && !failed && durationNanos < slowThresholdNanos) return;

        Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = matched == null ? UNMATCHED_ROUTE : matched.toString();
        int slot = routeSlots.getOrRegister(request.getMethod(), route, telemetry);
        if (slot == HttpRouteRegistry.DISABLED_SLOT) return;

        telemetry.recordHttp(slot, status, durationNanos, sampled && !failed ? sampleRate : 0);
        if (error != null) telemetry.recordError(slot, durationNanos, error);
    }

    private static int initialSampleOffset(long threadId, int mask) {
        long mixed = threadId * 0x9E3779B97F4A7C15L;
        mixed ^= mixed >>> 33;
        return (int) mixed & mask;
    }

    private static final class SampleState {
        private int remaining;

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

    private final class AsyncCompletion implements AsyncListener {
        private final Request request;
        private final Response response;
        private final AtomicBoolean completed = new AtomicBoolean();

        private AsyncCompletion(Request request, Response response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(null, 0);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            complete(new ServletException("Async request timed out"), 504);
        }

        @Override
        public void onError(AsyncEvent event) {
            complete(event.getThrowable(), 500);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void complete(Throwable error, int forcedStatus) {
            if (completed.compareAndSet(false, true)) {
                RustGlowrootTomcatValve.this.complete(request, response, error, forcedStatus);
            }
        }
    }
}
