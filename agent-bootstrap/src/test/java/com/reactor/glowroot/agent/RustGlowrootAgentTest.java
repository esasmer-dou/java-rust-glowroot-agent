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
                "collector=collector.platform.svc:8181,agent-id=ignored,trace-capacity=8,sample-rate=64"
        );

        assertEquals("collector.platform.svc:8181",
                System.getProperty("reactor.glowroot.collector.address"));
        assertEquals("explicit-agent", System.getProperty("reactor.glowroot.agent.id"));
        assertEquals("8", System.getProperty("reactor.glowroot.trace.capacity"));
        assertEquals("64", System.getProperty("reactor.glowroot.http.sample-rate"));
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
    void rejectsUnknownOrMalformedArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("unknown=value"));
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("reactor.glowroot.typo=value"));
        assertThrows(IllegalArgumentException.class,
                () -> RustGlowrootAgent.applyArguments("agent-id"));
    }
}
