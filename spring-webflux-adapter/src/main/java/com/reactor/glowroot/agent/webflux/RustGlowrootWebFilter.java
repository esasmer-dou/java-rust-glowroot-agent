package com.reactor.glowroot.agent.webflux;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional bounded WebFlux adapter with no dependency on a reactive server implementation. */
public final class RustGlowrootWebFilter implements WebFilter, Ordered {

    private final BoundedHttpTelemetry telemetry;
    private final int order;

    /** Creates a WebFlux filter backed by the process-scoped Rust telemetry runtime. */
    public RustGlowrootWebFilter(NativeTelemetry telemetry, TelemetryConfig config) {
        this((HttpTelemetrySink) telemetry, config, Ordered.HIGHEST_PRECEDENCE + 100);
    }

    /** Creates a WebFlux filter with an explicit Spring filter order. */
    public RustGlowrootWebFilter(
            NativeTelemetry telemetry,
            TelemetryConfig config,
            int order) {
        this((HttpTelemetrySink) telemetry, config, order);
    }

    RustGlowrootWebFilter(HttpTelemetrySink telemetry, TelemetryConfig config) {
        this(telemetry, config, Ordered.HIGHEST_PRECEDENCE + 100);
    }

    RustGlowrootWebFilter(
            HttpTelemetrySink telemetry,
            TelemetryConfig config,
            int order) {
        this.telemetry = new BoundedHttpTelemetry(telemetry, config);
        this.order = order;
    }

    @Override
    public int getOrder() {
        return order;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        int flags = telemetry.beginObservation();
        Observation observation = new Observation(
                exchange,
                flags,
                flags == 0 ? 0L : System.nanoTime());
        exchange.getResponse().beforeCommit(observation);
        try {
            return chain.filter(exchange).doOnError(observation);
        } catch (RuntimeException | Error error) {
            observation.accept(error);
            throw error;
        }
    }

    private void complete(Observation observation) {
        ServerWebExchange exchange = observation.exchange;
        HttpStatusCode responseStatus = exchange.getResponse().getStatusCode();
        int status = responseStatus == null ? 200 : responseStatus.value();
        Throwable error = observation.error;
        long durationNanos = observation.startedAtNanos == 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - observation.startedAtNanos);
        if (!telemetry.shouldRecord(observation.flags, status, durationNanos, error)) return;

        telemetry.record(
                observation.flags,
                exchange.getRequest().getMethod().name(),
                exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                status,
                durationNanos,
                error);
    }

    private final class Observation implements Supplier<Mono<Void>>, Consumer<Throwable> {
        private static final VarHandle COMPLETED;

        static {
            try {
                COMPLETED = MethodHandles.lookup().findVarHandle(
                        Observation.class,
                        "completed",
                        int.class);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private final ServerWebExchange exchange;
        private final int flags;
        private final long startedAtNanos;
        private Throwable error;
        @SuppressWarnings("unused")
        private volatile int completed;

        private Observation(
                ServerWebExchange exchange,
                int flags,
                long startedAtNanos) {
            this.exchange = exchange;
            this.flags = flags;
            this.startedAtNanos = startedAtNanos;
        }

        @Override
        public Mono<Void> get() {
            completeOnce();
            return Mono.empty();
        }

        @Override
        public void accept(Throwable error) {
            this.error = error;
            completeOnce();
        }

        private void completeOnce() {
            if (COMPLETED.compareAndSet(this, 0, 1)) complete(this);
        }
    }
}
