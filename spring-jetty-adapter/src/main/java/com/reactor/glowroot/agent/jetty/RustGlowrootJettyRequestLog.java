package com.reactor.glowroot.agent.jetty;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
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

        Throwable error = status >= 500
                && request.getAttribute(ERROR_ATTRIBUTE) instanceof Throwable throwable
                ? throwable
                : null;

        long startedAtNanos = request.getBeginNanoTime();
        long durationNanos = startedAtNanos <= 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - startedAtNanos);
        if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;
        telemetry.record(
                observation,
                request.getMethod(),
                request.getAttribute(ROUTE_ATTRIBUTE),
                status,
                durationNanos,
                error);
    }
}
