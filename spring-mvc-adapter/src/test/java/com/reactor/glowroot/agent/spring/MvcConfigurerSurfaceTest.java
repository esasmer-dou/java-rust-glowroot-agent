package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.junit.jupiter.api.Assertions.*;

class MvcConfigurerSurfaceTest {
    @Test
    void aggregateOnlyConfigurerDoesNotConstructOrInstallDiagnosticEnrichment() {
        var configurer = RustGlowrootMvcAutoConfiguration.createConfigurer(
                null, () -> { throw new AssertionError("Micro must not initialize diagnostics"); },
                configuration("micro", "0"), 0);
        configurer.addInterceptors(new InterceptorRegistry());
        var resolvers = new ArrayList<HandlerExceptionResolver>();
        configurer.extendHandlerExceptionResolvers(resolvers);
        assertTrue(resolvers.isEmpty());
    }

    @Test
    void selectedDetailCreatesOneEnricherAndDoesNotDuplicateExceptionResolver() {
        var constructions = new AtomicInteger();
        var configurer = RustGlowrootMvcAutoConfiguration.createConfigurer(
                null, () -> {
                    constructions.incrementAndGet();
                    return new RustGlowrootMvcEnricher(false);
                }, configuration("diagnostic", "8"), 0);
        var resolvers = new ArrayList<HandlerExceptionResolver>();
        configurer.addInterceptors(new InterceptorRegistry());
        configurer.extendHandlerExceptionResolvers(resolvers);
        configurer.extendHandlerExceptionResolvers(resolvers);
        assertEquals(1, constructions.get());
        assertEquals(1, resolvers.size());
    }

    private static TelemetryConfig configuration(String profile, String traces) {
        var properties = new HashMap<String, String>();
        properties.put("reactor.glowroot.agent.id", "mvc-configurer-test");
        properties.put("reactor.glowroot.profile", profile);
        properties.put("reactor.glowroot.trace.capacity", traces);
        return TelemetryConfig.from(properties::get);
    }
}
