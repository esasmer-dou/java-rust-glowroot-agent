package com.reactor.glowroot.agent.webflux;

import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RustGlowrootWebFilterTest {

    @Test
    void keepsTheConfiguredFrameworkBoundaryOrder() {
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(
                new FakeRecorder(),
                config(1, 0, 4),
                -1234);

        assertEquals(-1234, filter.getOrder());
    }

    @Test
    void recordsSuccessfulResponseWithTheResolvedRoute() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(recorder, config(1, 0, 4));
        MockServerWebExchange exchange = exchange("/orders/42");
        exchange.getAttributes().put(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                PathPatternParser.defaultInstance.parse("/orders/{id}"));

        StepVerifier.create(filter.filter(exchange, current -> current.getResponse().setComplete()))
                .verifyComplete();

        assertEquals(List.of("GET /orders/{id}"), recorder.routes);
        assertEquals(200, recorder.records.getFirst().status());
        assertEquals(1, recorder.records.getFirst().sampleWeight());
    }

    @Test
    void recordsAsynchronousCompletionOnce() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(recorder, config(1, 0, 4));
        MockServerWebExchange exchange = exchange("/async");
        exchange.getAttributes().put(
                HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                PathPatternParser.defaultInstance.parse("/async"));

        StepVerifier.create(filter.filter(
                        exchange,
                        current -> Mono.defer(current.getResponse()::setComplete)))
                .verifyComplete();

        assertEquals(1, recorder.records.size());
        assertEquals(200, recorder.records.getFirst().status());
    }

    @Test
    void recordsUnhandledFailureExactlyAfterMappedResponseCommit() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(recorder, config(1024, 0, 4));
        MockServerWebExchange exchange = exchange("/broken");

        Mono<Void> result = filter.filter(
                        exchange,
                        ignored -> Mono.error(new IllegalStateException("failed")))
                .onErrorResume(error -> {
                    exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    return exchange.getResponse().setComplete();
                });
        StepVerifier.create(result).verifyComplete();

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.getFirst().status());
        assertEquals(0, recorder.records.getFirst().sampleWeight());
        assertEquals(1, recorder.errors);
    }

    @Test
    void recordsAnUnmappedReactiveFailureBeforeResponseCommit() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(recorder, config(1024, 0, 4));
        MockServerWebExchange exchange = exchange("/broken");

        StepVerifier.create(filter.filter(
                        exchange,
                        ignored -> Mono.error(new IllegalArgumentException("failed"))))
                .expectError(IllegalArgumentException.class)
                .verify();

        assertEquals(1, recorder.records.size());
        assertEquals(500, recorder.records.getFirst().status());
        assertEquals(0, recorder.records.getFirst().sampleWeight());
        assertEquals(1, recorder.errors);
    }

    @Test
    void recordsSampled404AsUnmatched() {
        FakeRecorder recorder = new FakeRecorder();
        RustGlowrootWebFilter filter = new RustGlowrootWebFilter(recorder, config(1, 0, 4));
        MockServerWebExchange exchange = exchange("/missing");

        StepVerifier.create(filter.filter(exchange, current -> {
                    current.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return current.getResponse().setComplete();
                }))
                .verifyComplete();

        assertEquals(List.of("GET <unmatched>"), recorder.routes);
        assertEquals(404, recorder.records.getFirst().status());
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private static TelemetryConfig config(int sampleRate, int traceCapacity, int maxRoutes) {
        return new TelemetryConfig(
                "http://127.0.0.1:8181", "test::agent", "test-app", "localhost", "21",
                "OpenJ9", "test", 1, 1, 60_000, 1_000, 2_000, 500,
                sampleRate, traceCapacity, maxRoutes, 65_536);
    }

    private record Record(int status, int sampleWeight) {}

    private static final class FakeRecorder implements HttpTelemetrySink {
        private final List<String> routes = new ArrayList<>();
        private final List<Record> records = new ArrayList<>();
        private int errors;

        @Override
        public int registerHttpRoute(String method, String route) {
            routes.add(method + ' ' + route);
            return routes.size() - 1;
        }

        @Override
        public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
            records.add(new Record(status, sampleWeight));
        }

        @Override
        public void recordError(int slot, long durationNanos, Throwable error) {
            errors++;
        }
    }
}
