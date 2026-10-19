package com.reactor.glowroot.agent.runtime;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Explicit lifecycle for plain Java workers/providers. No Spring or HTTP server is started. */
public final class StandaloneTelemetry implements AutoCloseable {
    private static final StandaloneTelemetry DISABLED = new StandaloneTelemetry(null);
    private final NativeTelemetry telemetry;

    private StandaloneTelemetry(NativeTelemetry telemetry) { this.telemetry = telemetry; }

    public static StandaloneTelemetry startIfEnabled() {
        return startIfEnabled(key -> {
            String value = System.getProperty(key);
            return value != null ? value : System.getenv(key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT));
        });
    }

    public static StandaloneTelemetry startIfEnabled(Function<String, String> properties) {
        Objects.requireNonNull(properties);
        String enabled = properties.apply("reactor.glowroot.enabled");
        if (enabled == null || enabled.isBlank()) return DISABLED;
        enabled = enabled.trim();
        if (enabled.equalsIgnoreCase("false")) return DISABLED;
        if (!enabled.equalsIgnoreCase("true")) throw new IllegalArgumentException("reactor.glowroot.enabled must be true or false");
        return new StandaloneTelemetry(NativeTelemetry.start(TelemetryConfig.from(key -> {
            String value = properties.apply(key);
            return value == null || value.isBlank() ? null : value.trim();
        })));
    }

    /** Register fixed operation names once at startup, never customer IDs or payloads. */
    public NativeOperation operation(String type, String name) {
        return telemetry == null ? NativeOperation.NOOP : telemetry.operation(type, name);
    }
    public boolean enabled() { return telemetry != null; }
    public void updateProfile(TelemetryProfile profile) { if (telemetry != null) telemetry.updateProfile(profile); }
    public void restoreConfiguredProfile() { if (telemetry != null) telemetry.restoreConfiguredProfile(); }
    @Override public void close() { if (telemetry != null) telemetry.close(); }
}
