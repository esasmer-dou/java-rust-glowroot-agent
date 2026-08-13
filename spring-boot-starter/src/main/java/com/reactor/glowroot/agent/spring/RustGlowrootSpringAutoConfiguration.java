package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.EnumSet;

/** Opt-in Spring MVC integration. No transformer, scanner, or extra executor is installed. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({Filter.class, DispatcherServlet.class})
@ConditionalOnProperty(prefix = "reactor.glowroot", name = "enabled", havingValue = "true")
public class RustGlowrootSpringAutoConfiguration {

    /** Creates the opt-in Spring MVC adapter configuration. */
    public RustGlowrootSpringAutoConfiguration() {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(name = "rustGlowrootFilterRegistration")
    NativeTelemetry rustGlowrootNativeTelemetry(Environment environment) {
        return NativeTelemetry.start(TelemetryConfig.from(environment::getProperty));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "reactor.glowroot.spring",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    FilterRegistrationBean<RustGlowrootFilter> rustGlowrootFilterRegistration(
            NativeTelemetry telemetry,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        FilterRegistrationBean<RustGlowrootFilter> registration = new FilterRegistrationBean<>();
        registration.setName("rustGlowrootFilter");
        registration.setFilter(new RustGlowrootFilter(telemetry, config));
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setAsyncSupported(true);
        registration.addUrlPatterns("/*");
        int order = resolveFilterOrder(environment);
        registration.setOrder(order);
        return registration;
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
