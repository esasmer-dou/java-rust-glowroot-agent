package com.reactor.glowroot.benchmark;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryProfile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RestController
final class BenchmarkController {

    private static final byte[] RAW_JSON = "{\"status\":\"ok\",\"source\":\"precomputed\"}"
            .getBytes(StandardCharsets.UTF_8);
    private final ObjectProvider<NativeTelemetry> telemetry;

    BenchmarkController(ObjectProvider<NativeTelemetry> telemetry) {
        this.telemetry = telemetry;
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
}
