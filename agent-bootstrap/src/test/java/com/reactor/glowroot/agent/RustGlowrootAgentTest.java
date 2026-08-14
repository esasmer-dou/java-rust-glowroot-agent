package com.reactor.glowroot.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustGlowrootAgentTest {

    @AfterEach
    void clear() {
        System.getProperties().keySet().removeIf(key -> key.toString().startsWith(RustGlowrootAgent.PREFIX));
    }

    @Test
    void appliesShortAgentArgumentsWithoutOverwritingExplicitSystemProperties() {
        System.setProperty("reactor.glowroot.agent.id", "explicit-agent");

        RustGlowrootAgent.applyArguments(
                "collector=collector.platform.svc:8181,agent-id=ignored,trace-capacity=8,"
                        + "sample-rate=64,profile=full,profile-release-timeout-ms=7000,"
                        + "sql-capacity=12,error-trace-capacity=4,error-max-frames=16,"
                        + "error-max-bytes=2048,native-path=C:/native/rust_glowroot_agent.dll"
        );

        assertEquals("collector.platform.svc:8181",
                System.getProperty("reactor.glowroot.collector.address"));
        assertEquals("explicit-agent", System.getProperty("reactor.glowroot.agent.id"));
        assertEquals("8", System.getProperty("reactor.glowroot.trace.capacity"));
        assertEquals("64", System.getProperty("reactor.glowroot.http.sample-rate"));
        assertEquals("12", System.getProperty("reactor.glowroot.sql.capacity"));
        assertEquals("full", System.getProperty("reactor.glowroot.profile"));
        assertEquals("7000", System.getProperty("reactor.glowroot.profile.release-timeout-ms"));
        assertEquals("4", System.getProperty("reactor.glowroot.error.trace.capacity"));
        assertEquals("16", System.getProperty("reactor.glowroot.error.max-frames"));
        assertEquals("2048", System.getProperty("reactor.glowroot.error.max-bytes"));
        assertEquals(
                "C:/native/rust_glowroot_agent.dll",
                System.getProperty("reactor.glowroot.native.path")
        );
    }

    @Test
    void startEnablesAgentAndCapturesProcessStartTime() {
        RustGlowrootAgent.start("collector=localhost:8181,agent-id=sample::pod-1");

        assertEquals("true", System.getProperty("reactor.glowroot.enabled"));
        assertEquals("sample::pod-1", System.getProperty("reactor.glowroot.agent.id"));
        assertFalse(System.getProperty("reactor.glowroot.process-start-time-ms").isBlank());
    }

    @Test
    void defersRequiredCollectorIdentityValidationToFrameworkStartup() {
        RustGlowrootAgent.start(null);

        assertEquals("true", System.getProperty("reactor.glowroot.enabled"));
    }

    @Test
    void preservesAnExplicitDisabledSetting() {
        System.setProperty("reactor.glowroot.enabled", "false");

        RustGlowrootAgent.start("collector=localhost:8181,agent-id=disabled-agent");

        assertEquals("false", System.getProperty("reactor.glowroot.enabled"));
    }

    @Test
    void mapsSpringOrderAndKeepsTheFormerNamesAsAliases() {
        RustGlowrootAgent.applyArguments("spring-interceptor-order=42");

        assertEquals("42", System.getProperty("reactor.glowroot.spring.order"));

        System.clearProperty("reactor.glowroot.spring.order");
        RustGlowrootAgent.applyArguments("spring-filter-order=24");

        assertEquals("24", System.getProperty("reactor.glowroot.spring.order"));

        System.clearProperty("reactor.glowroot.spring.order");
        RustGlowrootAgent.applyArguments("spring-order=12");

        assertEquals("12", System.getProperty("reactor.glowroot.spring.order"));
    }

    @Test
    void rejectsUnknownOrMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("unknown=value"));
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("reactor.glowroot.typo=value"));
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("agent-id"));
    }
}
