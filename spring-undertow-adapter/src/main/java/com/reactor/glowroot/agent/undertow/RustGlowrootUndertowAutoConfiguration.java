package com.reactor.glowroot.agent.undertow;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetryAdapter;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration;
import io.undertow.UndertowOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Installs the direct Undertow root-handler completion adapter. */
@AutoConfiguration(
        after = RustGlowrootSpringAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration",
        beforeName = "com.reactor.glowroot.agent.spring.RustGlowrootMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "io.undertow.server.ExchangeCompletionListener",
        "org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory"
})
@ConditionalOnBean({NativeTelemetry.class, UndertowServletWebServerFactory.class})
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootUndertowAutoConfiguration {

    @Bean(name = "rustGlowrootUndertowTelemetryCustomizer")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    WebServerFactoryCustomizer<UndertowServletWebServerFactory> rustGlowrootUndertowTelemetryCustomizer(
            NativeTelemetry telemetry,
            Environment environment) {
        BoundedHttpTelemetry http = new BoundedHttpTelemetry(
                telemetry,
                TelemetryConfig.from(environment::getProperty));
        return factory -> {
            factory.addBuilderCustomizers(builder ->
                    builder.setServerOption(UndertowOptions.RECORD_REQUEST_START_TIME, true));
            factory.addDeploymentInfoCustomizers(deployment ->
                    deployment.addOuterHandlerChainWrapper(next ->
                            new RustGlowrootUndertowHandler(next, http)));
        };
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootUndertowTelemetryAdapter() {
        return new HttpTelemetryAdapter("undertow-completion-listener");
    }
}
