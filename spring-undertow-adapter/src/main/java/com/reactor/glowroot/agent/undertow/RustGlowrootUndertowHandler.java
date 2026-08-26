package com.reactor.glowroot.agent.undertow;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpThreadStats;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.util.AttachmentKey;
import jakarta.servlet.ServletRequest;

/** Undertow root handler using one reusable completion listener per application. */
final class RustGlowrootUndertowHandler implements HttpHandler {

    private static final String ROUTE_ATTRIBUTE =
            "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";
    private static final String ERROR_ATTRIBUTE = "jakarta.servlet.error.exception";
    private static final AttachmentKey<Throwable> FAILURE = AttachmentKey.create(Throwable.class);

    private final HttpHandler next;
    private final BoundedHttpTelemetry telemetry;
    private final ExchangeCompletionListener completionListener = this::complete;

    RustGlowrootUndertowHandler(HttpHandler next, BoundedHttpTelemetry telemetry) {
        this.next = next;
        this.telemetry = telemetry;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        exchange.addExchangeCompleteListener(completionListener);
        try {
            next.handleRequest(exchange);
        } catch (Exception error) {
            exchange.putAttachment(FAILURE, error);
            throw error;
        } catch (Error error) {
            exchange.putAttachment(FAILURE, error);
            throw error;
        }
    }

    private void complete(
            HttpServerExchange exchange,
            ExchangeCompletionListener.NextListener nextListener) {
        try {
            Throwable error = exchange.getAttachment(FAILURE);
            int status = exchange.getStatusCode();
            if (error != null && status < 500) status = 500;
            int observation = telemetry.observation(status);
            if (observation == 0) return;

            ServletRequestContext context = exchange.getAttachment(ServletRequestContext.ATTACHMENT_KEY);
            ServletRequest request = context == null ? null : context.getServletRequest();
            if (error == null && status >= 500 && request != null
                    && request.getAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE) instanceof Throwable captured) {
                error = captured;
            }
            if (error == null && status >= 500 && request != null
                    && request.getAttribute(ERROR_ATTRIBUTE) instanceof Throwable dispatched) {
                error = dispatched;
            }

            long startedAtNanos = exchange.getRequestStartTime();
            long durationNanos = startedAtNanos <= 0L
                    ? 0L
                    : Math.max(0L, System.nanoTime() - startedAtNanos);
                Object threadStatsStart = request == null
                        ? null
                        : request.getAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
                Object requestTraceValue = request == null
                        ? null
                        : request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
                if (request != null) {
                    request.removeAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE);
                    request.removeAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
                }
            if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;
            boolean detail = telemetry.shouldRecordDetail(observation, status, durationNanos, error);
            Object route = request == null ? null : request.getAttribute(ROUTE_ATTRIBUTE);
            telemetry.record(
                    observation,
                    detail ? exchange.getRequestMethod().toString() : "",
                    route,
                    status,
                    durationNanos,
                    error,
                    detail && request != null
                            ? request.getAttribute(HttpTraceMetadata.CONTROLLER_ATTRIBUTE) : null,
                    detail ? HttpThreadStats.finish(threadStatsStart) : null,
                    detail && requestTraceValue instanceof HttpRequestTraceState state ? state : null);
        } finally {
            nextListener.proceed();
        }
    }
}
