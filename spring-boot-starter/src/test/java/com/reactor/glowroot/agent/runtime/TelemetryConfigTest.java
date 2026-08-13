package com.reactor.glowroot.agent.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryConfigTest {

    @Test
    void resolvesBoundedConfigurationWithoutSpringBindingObjects() {
        Map<String, String> properties = new HashMap<>();
        properties.put("reactor.glowroot.agent.id", "orders::test");
        properties.put("reactor.glowroot.application.name", "orders-api");
        properties.put("reactor.glowroot.http.sample-rate", "64");
        properties.put("reactor.glowroot.max-routes", "16");

        TelemetryConfig config = TelemetryConfig.from(properties::get);

        assertEquals("orders::test", config.agentId());
        assertEquals("orders-api", config.applicationName());
        assertEquals(64, config.httpSampleRate());
        assertEquals(16, config.maxRoutes());
        assertEquals(60_000, config.exportIntervalMs());
    }

    @Test
    void rejectsMissingIdentityAndNonPowerOfTwoSampling() {
        assertThrows(IllegalArgumentException.class, () -> TelemetryConfig.from(key -> null));

        Map<String, String> properties = new HashMap<>();
        properties.put("reactor.glowroot.agent.id", "orders::test");
        properties.put("reactor.glowroot.http.sample-rate", "3");
        assertThrows(IllegalArgumentException.class, () -> TelemetryConfig.from(properties::get));
    }
}
