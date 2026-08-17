package com.reactor.glowroot.agent.tomcat;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetryAdapter;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration;
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

/** Installs the direct Tomcat request-lifecycle adapter. */
@AutoConfiguration(
        after = RustGlowrootSpringAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration",
        beforeName = "com.reactor.glowroot.agent.spring.RustGlowrootMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.apache.catalina.Valve",
        "org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory"
})
@ConditionalOnBean({NativeTelemetry.class, TomcatServletWebServerFactory.class})
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootTomcatAutoConfiguration {

    static final String CUSTOMIZER_BEAN = "rustGlowrootTomcatTelemetryCustomizer";

    @Bean(name = CUSTOMIZER_BEAN)
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> rustGlowrootTomcatTelemetryCustomizer(
            NativeTelemetry telemetry,
            Environment environment) {
        BoundedHttpTelemetry http = new BoundedHttpTelemetry(
                telemetry,
                TelemetryConfig.from(environment::getProperty));
        return factory -> factory.addContextValves(new RustGlowrootTomcatValve(http));
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootTomcatTelemetryAdapter() {
        return new HttpTelemetryAdapter("tomcat-valve");
    }
}
