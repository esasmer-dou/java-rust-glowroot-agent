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
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

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
        long startedAtNanos = flags == 0 ? 0L : System.nanoTime();
        try {
            return new ObservedMono(
                    chain.filter(exchange),
                    exchange,
                    flags,
                    startedAtNanos);
        } catch (RuntimeException | Error error) {
            completeFailOpen(exchange, flags, startedAtNanos, error);
            throw error;
        }
    }

    private void completeFailOpen(
            ServerWebExchange exchange,
            int flags,
            long startedAtNanos,
            Throwable error) {
        try {
            complete(exchange, flags, startedAtNanos, error);
        } catch (RuntimeException | LinkageError ignored) {
            // Native telemetry failures must not alter the application signal.
        }
    }

    private void complete(
            ServerWebExchange exchange,
            int flags,
            long startedAtNanos,
            Throwable error) {
        HttpStatusCode responseStatus = exchange.getResponse().getStatusCode();
        int status = responseStatus == null ? 200 : responseStatus.value();
        long durationNanos = startedAtNanos == 0L
                ? 0L
                : Math.max(0L, System.nanoTime() - startedAtNanos);
        if (!telemetry.shouldRecord(flags, status, durationNanos, error)) return;

        telemetry.record(
                flags,
                exchange.getRequest().getMethod().name(),
                exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                status,
                durationNanos,
                error);
    }

    private final class ObservedMono extends Mono<Void> {
        private final Mono<Void> source;
        private final ServerWebExchange exchange;
        private final int flags;
        private final long startedAtNanos;

        private ObservedMono(
                Mono<Void> source,
                ServerWebExchange exchange,
                int flags,
                long startedAtNanos) {
            this.source = source;
            this.exchange = exchange;
            this.flags = flags;
            this.startedAtNanos = startedAtNanos;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Void> actual) {
            source.subscribe(new CompletionSubscriber(actual, this));
        }
    }

    private final class CompletionSubscriber implements CoreSubscriber<Void> {
        private final CoreSubscriber<? super Void> actual;
        private final ObservedMono observation;

        private CompletionSubscriber(
                CoreSubscriber<? super Void> actual,
                ObservedMono observation) {
            this.actual = actual;
            this.observation = observation;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            actual.onSubscribe(subscription);
        }

        @Override
        public void onNext(Void ignored) {
            actual.onNext(ignored);
        }

        @Override
        public void onError(Throwable error) {
            actual.onError(error);
            completeAfterSignal(observation, error);
        }

        @Override
        public void onComplete() {
            actual.onComplete();
            completeAfterSignal(observation, null);
        }

        private void completeAfterSignal(ObservedMono observation, Throwable error) {
            completeFailOpen(
                    observation.exchange,
                    observation.flags,
                    observation.startedAtNanos,
                    error);
        }
    }
}
