package com.reactor.glowroot.agent.webflux;

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
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.WebFilter;

/** Activates the optional container-independent WebFlux HTTP boundary. */
@AutoConfiguration(after = RustGlowrootSpringAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass({WebFilter.class, DispatcherHandler.class})
@ConditionalOnBean(NativeTelemetry.class)
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootWebFluxAutoConfiguration {

    /** Creates one bounded WebFilter only when the optional adapter is present. */
    @Bean(name = "rustGlowrootWebFluxFilter")
    @ConditionalOnMissingBean(name = "rustGlowrootWebFluxFilter")
    RustGlowrootWebFilter rustGlowrootWebFluxFilter(
            NativeTelemetry telemetry,
            Environment environment) {
        return new RustGlowrootWebFilter(
                telemetry,
                TelemetryConfig.from(environment::getProperty),
                resolveFilterOrder(environment));
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootWebFluxTelemetryAdapter() {
        return new HttpTelemetryAdapter("webflux-filter");
    }

    private static int resolveFilterOrder(Environment environment) {
        Integer order = environment.getProperty("reactor.glowroot.spring.order", Integer.class);
        if (order == null) {
            order = environment.getProperty("reactor.glowroot.spring.interceptor-order", Integer.class);
        }
        if (order == null) {
            order = environment.getProperty("reactor.glowroot.spring.filter-order", Integer.class);
        }
        return order == null ? Integer.MIN_VALUE + 100 : order;
    }
}
