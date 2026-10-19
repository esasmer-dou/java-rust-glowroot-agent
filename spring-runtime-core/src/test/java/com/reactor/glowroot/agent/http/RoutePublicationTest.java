package com.reactor.glowroot.agent.http;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoutePublicationTest {
    @Test void concurrentCollidingRoutesPublishTheSlotBeforeAnyReaderUsesIt() throws Exception {
        var names = List.of("/Aa", "/BB", "/AaAa", "/BBBB", "/AaBB", "/BBAa");
        var sink = new Recorder();
        var http = new BoundedHttpTelemetry(sink, config());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                final int offset = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        http.recordAggregate(names.get((i + offset) % names.size()), 200, i, null);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertEquals(names.size(), sink.registrations.get());
        assertEquals(16_000, sink.records.size());
        assertTrue(sink.records.stream().allMatch(slot -> slot >= 1 && slot <= names.size()));
        assertEquals(names.size(), sink.records.stream().distinct().count());
    }

    @Test void disabledRegistrationDoesNotPublishAPartialRouteOrDiscardEarlierEntries() {
        var attempts = new AtomicInteger();
        var records = new java.util.ArrayList<Integer>();
        var sink = new HttpTelemetrySink() {
            @Override public int registerHttpRoute(String method, String route) {
                if (route.equals("/new") && attempts.incrementAndGet() == 1) return -1;
                return route.equals("/ready") ? 1 : 2;
            }
            @Override public void recordHttp(int slot, int status, long nanos, int weight) { records.add(slot); }
        };
        var http = new BoundedHttpTelemetry(sink, config());
        http.recordAggregate("/ready", 200, 1, null);
        http.recordAggregate("/new", 200, 1, null);
        http.recordAggregate("/ready", 200, 1, null);
        http.recordAggregate("/new", 200, 1, null);
        assertEquals(List.of(1, 1, 2), records);
        assertEquals(2, attempts.get());
    }

    private static TelemetryConfig config() {
        return new TelemetryConfig("http://127.0.0.1:1", "test", "test", "localhost",
                "21", "OpenJ9", "test", 1L, 1L, 60_000, 100, 100, 500, 256, 0, 8, 16_384);
    }

    private static final class Recorder implements HttpTelemetrySink {
        final AtomicInteger registrations = new AtomicInteger();
        final ConcurrentHashMap<String, Integer> slots = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Integer> records = new ConcurrentLinkedQueue<>();
        @Override public int registerHttpRoute(String method, String route) {
            int slot = registrations.incrementAndGet();
            assertNull(slots.putIfAbsent(route, slot), "A route was registered twice");
            return slot;
        }
        @Override public void recordHttp(int slot, int status, long nanos, int weight) { records.add(slot); }
    }
}
