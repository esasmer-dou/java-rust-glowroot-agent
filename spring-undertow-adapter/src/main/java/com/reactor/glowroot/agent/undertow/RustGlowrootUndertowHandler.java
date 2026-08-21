package com.reactor.glowroot.agent.undertow;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
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
                    && request.getAttribute(ERROR_ATTRIBUTE) instanceof Throwable throwable) {
                error = throwable;
            }

            long startedAtNanos = exchange.getRequestStartTime();
            long durationNanos = startedAtNanos <= 0L
                    ? 0L
                    : Math.max(0L, System.nanoTime() - startedAtNanos);
            if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;
            Object route = request == null ? null : request.getAttribute(ROUTE_ATTRIBUTE);
            telemetry.record(
                    observation,
                    route,
                    status,
                    durationNanos,
                    error);
        } finally {
            nextListener.proceed();
        }
    }
}
