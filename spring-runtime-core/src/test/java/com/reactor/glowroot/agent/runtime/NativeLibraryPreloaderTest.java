package com.reactor.glowroot.agent.runtime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryPreloaderTest {
    @Test
    void bootDiscoversThePreloaderWithoutComponentScanning() throws Exception {
        var properties = new java.util.Properties();
        try (var input = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertNotNull(input);
            properties.load(input);
        }
        assertEquals(NativeLibraryPreloader.class.getName(),
                properties.getProperty("org.springframework.boot.env.EnvironmentPostProcessor"));
    }

    @Test
    void disabledOrAbsentNeverLoadsTheNativeImage() {
        AtomicInteger loads = new AtomicInteger();
        NativeLibraryPreloader preloader = new NativeLibraryPreloader(environment -> loads.incrementAndGet());
        StandardEnvironment environment = environment(Map.of());
        preloader.postProcessEnvironment(environment, null);
        environment.getPropertySources().addFirst(new MapPropertySource("off", Map.of("reactor.glowroot.enabled", "false")));
        preloader.postProcessEnvironment(environment, null);
        assertEquals(0, loads.get());
    }

    @Test
    void enabledUsesResolvedConfigDataWithoutStartingAnEngine() {
        StandardEnvironment environment = environment(Map.of(
                "reactor.glowroot.enabled", "true",
                "reactor.glowroot.native.path", "/opt/agent/agent.so"));
        AtomicInteger loads = new AtomicInteger();
        NativeLibraryPreloader preloader = new NativeLibraryPreloader(resolved -> {
            assertSame(environment, resolved);
            assertEquals("/opt/agent/agent.so", resolved.getProperty("reactor.glowroot.native.path"));
            loads.incrementAndGet();
        });
        preloader.postProcessEnvironment(environment, null);
        assertEquals(1, loads.get());
        assertEquals(Ordered.LOWEST_PRECEDENCE, preloader.getOrder());
    }

    @Test
    void aBadNativeImageStillFailsStartupRatherThanSilentlyDisablingTelemetry() {
        NativeLibraryPreloader preloader = new NativeLibraryPreloader(environment -> {
            throw new IllegalStateException("hash mismatch");
        });
        var error = assertThrows(IllegalStateException.class, () -> preloader.postProcessEnvironment(
                environment(Map.of("reactor.glowroot.enabled", "true")), null));
        assertEquals("hash mismatch", error.getMessage());
    }

    private static StandardEnvironment environment(Map<String, Object> values) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("config-data", values));
        return environment;
    }
}
