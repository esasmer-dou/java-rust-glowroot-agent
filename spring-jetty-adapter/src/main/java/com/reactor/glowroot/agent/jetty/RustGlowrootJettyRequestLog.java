package com.reactor.glowroot.agent.jetty;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpThreadStats;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Response;

/** Jetty completion callback using the server's existing request clock. */
final class RustGlowrootJettyRequestLog implements RequestLog {

    private static final String ROUTE_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    private static final String ERROR_ATTRIBUTE = "jakarta.servlet.error.exception";

    private final BoundedHttpTelemetry telemetry;

    RustGlowrootJettyRequestLog(BoundedHttpTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void log(Request request, Response response) {
        int status = response.getStatus();
        int observation = telemetry.observation(status);
        if (observation == 0) return;

        Throwable error = null;
        if (status >= 500) {
            Object captured = request.getAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE);
            if (!(captured instanceof Throwable)) captured = request.getAttribute(ERROR_ATTRIBUTE);
            if (captured instanceof Throwable throwable) error = throwable;
        }

        long startedAtNanos = request.getBeginNanoTime();
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
}
