package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Tomcat fast path; other Servlet containers retain the portable MVC interceptor. */
@AutoConfiguration(
        after = RustGlowrootSpringAutoConfiguration.class,
        before = RustGlowrootMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.apache.catalina.Valve",
        "org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory"
})
@ConditionalOnBean(NativeTelemetry.class)
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootTomcatAutoConfiguration {

    static final String CUSTOMIZER_BEAN = "rustGlowrootTomcatValveCustomizer";

    /** Installs one bounded context valve without adding a filter or interceptor. */
    @Bean(name = CUSTOMIZER_BEAN)
    @ConditionalOnMissingBean(name = CUSTOMIZER_BEAN)
    @ConditionalOnProperty(
            prefix = "reactor.glowroot.spring.tomcat-native",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> rustGlowrootTomcatValveCustomizer(
            NativeTelemetry telemetry,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        return factory -> factory.addContextValves(new RustGlowrootTomcatValve(telemetry, config));
    }
}
