package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Web-independent native telemetry lifecycle.
 *
 * <p>This configuration intentionally has no Servlet or Spring MVC type references. Kafka workers,
 * schedulers, command-line services, and database-only Spring Boot applications can therefore use
 * process, JVM, explicit SQL, error, and diagnostic telemetry without adding a web stack.</p>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "reactor.glowroot", name = "enabled", havingValue = "true")
public class RustGlowrootSpringAutoConfiguration {

    /** Creates the opt-in, process-scoped native telemetry runtime. */
    public RustGlowrootSpringAutoConfiguration() {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(
            value = NativeTelemetry.class,
            name = "rustGlowrootFilterRegistration")
    NativeTelemetry rustGlowrootNativeTelemetry(Environment environment) {
        return NativeTelemetry.start(TelemetryConfig.from(environment::getProperty));
    }
}
