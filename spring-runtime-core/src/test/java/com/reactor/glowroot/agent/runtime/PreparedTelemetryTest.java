package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedTelemetryTest {
    @Test
    void preparationAndImmutableSettingsDoNotResolveOrLoadNativeCode() {
        TelemetryConfig config = config("micro");
        NativeTelemetry previous = NativeTelemetry.current();
        try (NativeTelemetry prepared = NativeTelemetry.prepare(config, key -> {
            throw new AssertionError("Prepared facade loaded native code");
        })) {
            assertFalse(prepared.isStarted());
            assertSame(prepared, NativeTelemetry.current());
            assertSame(config, prepared.configuration());
            assertEquals(config.maxRoutes(), prepared.maxRoutes());
            assertEquals(config.profile(), prepared.configuredProfile());
        }
        assertSame(previous, NativeTelemetry.current());
    }

    @Test
    void closingPreparedHandlesOutOfOrderRestoresThePreviousFacade() {
        TelemetryConfig config = config("micro");
        NativeTelemetry previous = NativeTelemetry.current();
        try (NativeTelemetry first = NativeTelemetry.prepare(config);
             NativeTelemetry second = NativeTelemetry.prepare(config)) {
            first.close();
            assertSame(second, NativeTelemetry.current());
            assertFalse(second.isStarted());
        }
        assertSame(previous, NativeTelemetry.current());
    }

    @Test
    void preparedHandlesAlreadyEnforceOneProcessStartupProfile() {
        TelemetryConfig micro = config("micro");
        TelemetryConfig diagnostic = config("diagnostic");
        try (NativeTelemetry prepared = NativeTelemetry.prepare(micro)) {
            assertThrows(IllegalStateException.class, () -> NativeTelemetry.prepare(diagnostic));
            assertSame(prepared, NativeTelemetry.current());
            assertFalse(prepared.isStarted());
        }
    }

    @Test
    void closedPreparationCannotActivateAndReleasesItsSettingResolver() throws Exception {
        NativeTelemetry prepared = NativeTelemetry.prepare(config("micro"), key -> null);
        prepared.close();
        assertThrows(IllegalStateException.class, prepared::activate);
        var resolver = NativeTelemetry.class.getDeclaredField("nativeProperties");
        resolver.setAccessible(true);
        assertNull(resolver.get(prepared));
    }

    private static TelemetryConfig config(String profile) {
        return TelemetryConfig.from(key -> switch (key) {
            case "reactor.glowroot.agent.id" -> "prepared-test";
            case "reactor.glowroot.profile" -> profile;
            default -> null;
        });
    }
}
