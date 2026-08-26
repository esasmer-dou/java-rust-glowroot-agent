package com.reactor.glowroot.agent.tomcat;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpThreadStats;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Tomcat-native completion edge with no Spring interceptor on the request path. */
final class RustGlowrootTomcatValve extends ValveBase {

    private static final String ROUTE_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    private static final String ERROR_ATTRIBUTE = "jakarta.servlet.error.exception";

    private final BoundedHttpTelemetry telemetry;

    RustGlowrootTomcatValve(BoundedHttpTelemetry telemetry) {
        super(true);
        this.telemetry = telemetry;
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
        if (error == null && status >= 500) {
            Object captured = request.getAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE);
            if (!(captured instanceof Throwable)) captured = request.getAttribute(ERROR_ATTRIBUTE);
            if (captured instanceof Throwable dispatchedError) error = dispatchedError;
        }
        if (error != null && status < 500) status = 500;
        int observation = telemetry.observation(status);
        if (observation == 0) return;

        long startedAtNanos = request.getCoyoteRequest().getStartTimeNanos();
        long durationNanos = startedAtNanos <= 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - startedAtNanos);
        Object threadStatsStart = request.getAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
        request.removeAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
        Object requestTraceValue = request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
        request.removeAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
        if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;
        boolean detail = telemetry.shouldRecordDetail(observation, status, durationNanos, error);
        telemetry.record(
                observation,
                detail ? request.getMethod() : "",
                request.getAttribute(ROUTE_ATTRIBUTE),
                status,
                durationNanos,
                error,
                detail ? request.getAttribute(HttpTraceMetadata.CONTROLLER_ATTRIBUTE) : null,
                detail ? HttpThreadStats.finish(threadStatsStart) : null,
                detail && requestTraceValue instanceof HttpRequestTraceState state ? state : null);
    }

    private final class AsyncCompletion implements AsyncListener {
        private static final VarHandle COMPLETED;

        static {
            try {
                COMPLETED = MethodHandles.lookup().findVarHandle(
                        AsyncCompletion.class,
                        "completed",
                        int.class);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private final Request request;
        private final Response response;
        @SuppressWarnings("unused")
        private volatile int completed;

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
            if (COMPLETED.compareAndSet(this, 0, 1)) {
                RustGlowrootTomcatValve.this.complete(request, response, error, forcedStatus);
            }
        }
    }
}
