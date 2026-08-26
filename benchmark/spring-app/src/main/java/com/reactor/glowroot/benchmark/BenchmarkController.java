package com.reactor.glowroot.benchmark;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import com.reactor.glowroot.agent.http.HttpTelemetryAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@RestController
final class BenchmarkController {

    private static final byte[] RAW_JSON = "{\"status\":\"ok\",\"source\":\"precomputed\"}"
            .getBytes(StandardCharsets.UTF_8);
    private final ObjectProvider<NativeTelemetry> telemetry;
    private final ObjectProvider<HttpTelemetryAdapter> httpAdapter;

    BenchmarkController(
            ObjectProvider<NativeTelemetry> telemetry,
            ObjectProvider<HttpTelemetryAdapter> httpAdapter) {
        this.telemetry = telemetry;
        this.httpAdapter = httpAdapter;
    }

    @GetMapping(path = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    HealthResponse health() {
        return new HealthResponse("UP");
    }

    @GetMapping(path = "/api/small", produces = MediaType.APPLICATION_JSON_VALUE)
    SmallResponse small(@RequestParam(name = "id", defaultValue = "42") int id) {
        return new SmallResponse(id, "active", "Istanbul");
    }

    @GetMapping(path = "/api/raw", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<byte[]> raw() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(RAW_JSON);
    }

    @PostMapping(path = "/internal/telemetry/profile", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ProfileResponse> profile(@RequestParam("name") String name) {
        NativeTelemetry active = telemetry.getIfAvailable();
        if (active == null) {
            return ResponseEntity.status(409).body(new ProfileResponse("disabled"));
        }
        TelemetryProfile requested = TelemetryProfile.parse(name);
        active.updateProfile(requested, Duration.ofSeconds(5));
        return ResponseEntity.ok(new ProfileResponse(active.activeProfile().propertyValue()));
    }

    @PostMapping(path = "/internal/benchmark/full-gc")
    ResponseEntity<Void> fullGc() {
        System.gc();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(path = "/internal/benchmark/telemetry-adapter", produces = MediaType.APPLICATION_JSON_VALUE)
    AdapterResponse telemetryAdapter() {
        HttpTelemetryAdapter active = httpAdapter.getIfAvailable();
        return new AdapterResponse(
                telemetry.getIfAvailable() != null,
                active == null ? "none" : active.id(),
                active != null
        );
    }

    @GetMapping(path = "/internal/benchmark/telemetry-diagnostics", produces = MediaType.APPLICATION_JSON_VALUE)
    String telemetryDiagnostics() {
        NativeTelemetry active = telemetry.getIfAvailable();
        return active == null ? "{\"enabled\":false}" : active.diagnosticsJson();
    }

    @GetMapping(path = "/internal/benchmark/async", produces = MediaType.APPLICATION_JSON_VALUE)
    CompletionStage<HealthResponse> async() {
        return CompletableFuture.completedFuture(new HealthResponse("UP"));
    }

    @GetMapping(path = "/internal/benchmark/error")
    void error() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "expected benchmark error");
    }

    @GetMapping(path = "/api/heavy", produces = MediaType.APPLICATION_JSON_VALUE)
    HeavyResponse heavy(@RequestParam(name = "items", defaultValue = "100") int items) {
        int boundedItems = Math.max(1, Math.min(items, 250));
        List<HeavyItem> values = new ArrayList<>(boundedItems);
        for (int index = 0; index < boundedItems; index++) {
            values.add(new HeavyItem(index, "product-" + index, index * 17L, index % 5 == 0));
        }
        return new HeavyResponse(values.size(), values);
    }

    record HealthResponse(String status) {}

    record SmallResponse(int id, String status, String city) {}

    record HeavyResponse(int count, List<HeavyItem> items) {}

    record HeavyItem(int id, String name, long score, boolean featured) {}

    record ProfileResponse(String profile) {}

    record AdapterResponse(
            boolean enabled,
            String httpAdapter,
            boolean fullLifecycle) {}
}
