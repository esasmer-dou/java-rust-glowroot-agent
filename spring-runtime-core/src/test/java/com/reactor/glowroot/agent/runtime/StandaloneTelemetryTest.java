package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneTelemetryTest {
    @Test void disabledDoesNotResolveConfigurationOrLoadNative() {
        try (var telemetry = StandaloneTelemetry.startIfEnabled(key -> {
            assertEquals("reactor.glowroot.enabled", key);
            return "false";
        })) {
            assertFalse(telemetry.enabled());
            var op = telemetry.operation("Scheduler", "customer-refresh");
            assertSame(NativeOperation.NOOP, op);
            Runnable work = () -> {};
            assertSame(work, op.wrap(work));
            assertEquals(0, op.start());
        }
    }
    @Test void invalidBooleanFailsBeforeNativeLoading() {
        assertThrows(IllegalArgumentException.class, () -> StandaloneTelemetry.startIfEnabled(key -> "treu"));
    }
    @Test void missingOrBlankOptionalValuesRemainDisabled() {
        assertFalse(StandaloneTelemetry.startIfEnabled(key -> null).enabled());
        assertFalse(StandaloneTelemetry.startIfEnabled(key -> "").enabled());
        assertFalse(StandaloneTelemetry.startIfEnabled(key -> " false ").enabled());
        NativeOperation.NOOP.complete(0,false);
    }
}
