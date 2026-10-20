package com.reactor.glowroot.agent.tomcat;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpThreadStats;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import jakarta.servlet.ServletException;
import org.apache.catalina.AccessLog;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;

/** Uses Tomcat's terminal completion callback; no request timer or agent async listener. */
final class RustGlowrootTomcatValve extends ValveBase implements AccessLog {

    private static final String ROUTE_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    private static final String ERROR_ATTRIBUTE = "jakarta.servlet.error.exception";

    private final BoundedHttpTelemetry telemetry;
    private boolean requestAttributesEnabled;

    RustGlowrootTomcatValve(BoundedHttpTelemetry telemetry) {
        super(true);
        this.telemetry = telemetry;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        getNext().invoke(request, response);
    }

    @Override
    public void log(Request request, Response response, long durationNanos) {
        // Tomcat calls AccessLog after sync/async completion, before recycling attributes.
        // Its Tomcat 10+ duration is nanoseconds, including async waiting and final I/O.
        try {
            complete(request, response, Math.max(0L, durationNanos));
        } catch (RuntimeException ignored) {
            // Telemetry must not interrupt response completion or connection recycling.
        }
    }

    private void complete(Request request, Response response, long durationNanos) {
        int status = response == null ? 500 : response.getStatus();
        Throwable error = response != null && response.getCoyoteResponse() != null
                ? response.getCoyoteResponse().getErrorException() : null;
        if (error == null && status >= 500 && request != null) {
            Object captured = request.getAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE);
            if (!(captured instanceof Throwable)) captured = request.getAttribute(ERROR_ATTRIBUTE);
            if (captured instanceof Throwable dispatchedError) error = dispatchedError;
        }
        if (error != null && status < 500) status = 500;
        Object route = request == null ? null : request.getAttribute(ROUTE_ATTRIBUTE);
        if (telemetry.aggregateOnly() || request == null) {
            telemetry.recordAggregate(route, status, durationNanos, error);
            return;
        }
        int observation = telemetry.observation(status);
        if (observation == 0) return;
        Object threadStatsStart = request.getAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
        request.removeAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
        Object requestTraceValue = request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
        request.removeAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
        if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;
        boolean detail = telemetry.shouldRecordDetail(observation, status, durationNanos, error);
        telemetry.record(
                observation,
                detail ? request.getMethod() : "",
                route,
                status,
                durationNanos,
                error,
                detail ? request.getAttribute(HttpTraceMetadata.CONTROLLER_ATTRIBUTE) : null,
                detail ? HttpThreadStats.finish(threadStatsStart) : null,
                detail && requestTraceValue instanceof HttpRequestTraceState state ? state : null);
    }

    @Override
    public void setRequestAttributesEnabled(boolean enabled) { requestAttributesEnabled = enabled; }

    @Override
    public boolean getRequestAttributesEnabled() { return requestAttributesEnabled; }
}
