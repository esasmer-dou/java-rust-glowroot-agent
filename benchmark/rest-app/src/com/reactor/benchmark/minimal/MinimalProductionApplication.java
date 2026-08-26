package com.reactor.benchmark.minimal;

import com.reactor.rust.annotations.DirectQueryInt;
import com.reactor.rust.annotations.NativeStaticRoute;
import com.reactor.rust.annotations.RouteAdmission;
import com.reactor.rust.annotations.RouteWorkload;
import com.reactor.rust.annotations.RustRoute;
import com.reactor.rust.bridge.HandlerRegistry;
import com.reactor.rust.bridge.NativeBridge;
import com.reactor.rust.bridge.RouteScanner;
import com.reactor.rust.config.PropertiesLoader;
import com.reactor.rust.config.RuntimeFootprintGate;
import com.reactor.rust.config.RuntimeProfiles;
import com.reactor.rust.di.annotation.Component;
import com.reactor.rust.http.RawResponse;
import com.reactor.rust.json.JsonBufferWriter;
import com.reactor.rust.logging.FrameworkLogger;
import com.reactor.rust.memory.NativeIdleMemoryTrimmer;
import com.reactor.rust.metrics.Metrics;
import com.reactor.rust.metrics.MetricsHandler;
import com.reactor.rust.startup.StartupTimeline;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal production fixture owned by the agent release gate. */
public final class MinimalProductionApplication {
    private static final byte[] TEST_PREFIX = ascii("test");
    private static final byte[] CANDIDATE_PREFIX = ascii("CAND-");

    private MinimalProductionApplication() {
    }

    public static void main(String[] args) {
        StartupTimeline.mark("minimal.main.enter");
        PropertiesLoader.load();
        RuntimeProfiles.apply();
        RuntimeFootprintGate.validate();

        HandlerRegistry registry = HandlerRegistry.getInstance();
        registry.registerBean(new MinimalHandler());
        boolean metricsEnabled = PropertiesLoader.getBoolean("reactor.benchmark.metrics-enabled", true);
        Metrics.getInstance().configureCollection(metricsEnabled);
        if (metricsEnabled) {
            registry.registerBean(new MetricsHandler());
        }

        NativeBridge.configureRuntimeFromProperties();
        RouteScanner.scanAndRegister();
        AtomicReference<NativeIdleMemoryTrimmer> nativeIdleTrimmer = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            NativeIdleMemoryTrimmer trimmer = nativeIdleTrimmer.get();
            if (trimmer != null) {
                trimmer.close();
            }
            try {
                NativeBridge.stopHttpServer();
            } catch (UnsatisfiedLinkError ignored) {
                // Startup can fail before the native runtime is available.
            }
        }, "minimal-rust-hyper-shutdown"));

        int port = PropertiesLoader.getInt("server.port", 8080);
        NativeBridge.startHttpServer(port);
        StartupTimeline.ready();
        nativeIdleTrimmer.set(NativeIdleMemoryTrimmer.startFromProperties());
        FrameworkLogger.info("[JAVA] Minimal production app ready in "
                + StartupTimeline.readyMillis() + " ms");

        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Component
    public static final class MinimalHandler {
        private static final RawResponse HEALTH = RawResponse.text(
                "{\"status\":\"UP\"}",
                "application/json; charset=utf-8"
        );
        private static final byte[] SMALL_ORDER_BYTES = buildSmallOrderBytes();
        private static final byte[] RAW_HEAVY_100 = buildHeavyJsonBytes(100);

        @RustRoute(method = "GET", path = "/health", requestType = Void.class,
                responseType = RawResponse.class)
        public RawResponse health() {
            return HEALTH;
        }

        @RustRoute(method = "GET", path = "/api/v1/candidates/direct", requestType = Void.class,
                responseType = RawResponse.class)
        @NativeStaticRoute
        public RawResponse candidatesDirect() {
            return RawResponse.registeredJson(SMALL_ORDER_BYTES);
        }

        @RustRoute(method = "GET", path = "/api/v1/heavy", requestType = Void.class,
                responseType = MinimalHeavyResponse.class)
        @DirectQueryInt(value = "items", defaultValue = 100, min = 1, max = 1000)
        @RouteWorkload(value = RouteWorkload.Type.HEAVY_JSON, budget = "heavy-json-direct")
        @RouteAdmission(maxConcurrent = 96, queueTimeoutMs = 75)
        public int heavyDirect(ByteBuffer out, int offset, int itemCount) {
            return writeHeavyJson(out, offset, itemCount, System.currentTimeMillis());
        }

        @RustRoute(method = "GET", path = "/api/v1/heavy/raw", requestType = Void.class,
                responseType = RawResponse.class)
        @NativeStaticRoute
        public RawResponse heavyRaw() {
            return RawResponse.registeredJson(RAW_HEAVY_100);
        }
    }

    public record MinimalHeavyResponse(int items, long generatedAt) {
    }

    private static int writeSmallOrder(ByteBuffer out, int offset) {
        JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset);
        writer.beginObject()
                .fieldString("orderId", "ORD-1001").comma()
                .fieldFixed2Cents("total", 35075).comma()
                .fieldBoolean("active", true).comma()
                .fieldName("items").beginArray();
        for (int i = 0; i < 19; i++) {
            if (i > 0) {
                writer.comma();
            }
            writer.beginObject()
                    .fieldStringAsciiPrefixInt("sku", TEST_PREFIX, i).comma()
                    .fieldFixed2Cents("price", 1289 + i)
                    .endObject();
        }
        writer.endArray().endObject();
        return writer.result();
    }

    private static byte[] buildSmallOrderBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        int written = writeSmallOrder(buffer, 0);
        if (written < 0) {
            buffer = ByteBuffer.allocate(-written);
            written = writeSmallOrder(buffer, 0);
        }
        if (written < 0) {
            throw new IllegalStateException("Precomputed small order JSON exceeded buffer: " + -written);
        }
        byte[] bytes = new byte[written];
        buffer.position(0);
        buffer.get(bytes, 0, written);
        return bytes;
    }

    private static int writeHeavyJson(ByteBuffer out, int offset, int items, long generatedAt) {
        JsonBufferWriter writer = JsonBufferWriter.reusable(out, offset);
        writer.beginObject()
                .fieldInt("items", items).comma()
                .fieldLong("generatedAt", generatedAt).comma()
                .fieldName("rows").beginArray();
        for (int i = 0; i < items; i++) {
            if (i > 0) {
                writer.comma();
            }
            writer.beginObject()
                    .fieldInt("id", i).comma()
                    .fieldStringAsciiPrefixInt("code", CANDIDATE_PREFIX, i).comma()
                    .fieldFixed2Cents("amount", 10000L + (long) i * 17L).comma()
                    .fieldBoolean("active", (i & 1) == 0)
                    .endObject();
        }
        writer.endArray().endObject();
        return writer.result();
    }

    private static byte[] buildHeavyJsonBytes(int items) {
        ByteBuffer buffer = ByteBuffer.allocate(128 * 1024);
        int written = writeHeavyJson(buffer, 0, items, 1_700_000_000_000L);
        if (written < 0) {
            throw new IllegalStateException("Precomputed heavy JSON exceeded buffer: " + -written);
        }
        byte[] bytes = new byte[written];
        buffer.position(0);
        buffer.get(bytes, 0, written);
        return bytes;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
