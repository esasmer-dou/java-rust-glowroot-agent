package com.reactor.glowroot.agent.jetty;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetryAdapter;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration;
import org.eclipse.jetty.server.RequestLog;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Installs the direct Jetty completion adapter. */
@AutoConfiguration(
        after = RustGlowrootSpringAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration",
        beforeName = "com.reactor.glowroot.agent.spring.RustGlowrootMvcAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "org.eclipse.jetty.server.RequestLog",
        "org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory"
})
@ConditionalOnBean({NativeTelemetry.class, JettyServletWebServerFactory.class})
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootJettyAutoConfiguration {

    @Bean(name = "rustGlowrootJettyTelemetryCustomizer")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    WebServerFactoryCustomizer<JettyServletWebServerFactory> rustGlowrootJettyTelemetryCustomizer(
            NativeTelemetry telemetry,
            Environment environment) {
        BoundedHttpTelemetry http = new BoundedHttpTelemetry(
                telemetry,
                TelemetryConfig.from(environment::getProperty));
        return factory -> factory.addServerCustomizers(server -> {
            RequestLog telemetryLog = new RustGlowrootJettyRequestLog(http);
            RequestLog existing = server.getRequestLog();
            server.setRequestLog(existing == null
                    ? telemetryLog
                    : new CompositeRequestLog(telemetryLog, existing));
        });
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootJettyTelemetryAdapter() {
        return new HttpTelemetryAdapter("jetty-request-log");
    }

    private record CompositeRequestLog(RequestLog telemetry, RequestLog existing)
            implements RequestLog {
        @Override
        public void log(
                org.eclipse.jetty.server.Request request,
                org.eclipse.jetty.server.Response response) {
            telemetry.log(request, response);
            existing.log(request, response);
        }
    }
}
