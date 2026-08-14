package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Opt-in Spring MVC integration. No transformer, scanner, request filter, or extra executor is installed. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({AsyncHandlerInterceptor.class, DispatcherServlet.class})
@ConditionalOnProperty(prefix = "reactor.glowroot", name = "enabled", havingValue = "true")
public class RustGlowrootSpringAutoConfiguration {

    /** Creates the opt-in Spring MVC adapter configuration. */
    public RustGlowrootSpringAutoConfiguration() {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(
            value = NativeTelemetry.class,
            name = "rustGlowrootFilterRegistration")
    NativeTelemetry rustGlowrootNativeTelemetry(Environment environment) {
        return NativeTelemetry.start(TelemetryConfig.from(environment::getProperty));
    }

    @Bean
    @ConditionalOnMissingBean(
            value = RustGlowrootInterceptor.class,
            name = "rustGlowrootFilterRegistration")
    @ConditionalOnProperty(
            prefix = "reactor.glowroot.spring",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    RustGlowrootInterceptor rustGlowrootInterceptor(
            NativeTelemetry telemetry,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        return new RustGlowrootInterceptor(telemetry, config);
    }

    @Bean
    @ConditionalOnMissingBean(name = {
            "rustGlowrootWebMvcConfigurer",
            "rustGlowrootFilterRegistration"
    })
    @ConditionalOnProperty(
            prefix = "reactor.glowroot.spring",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    WebMvcConfigurer rustGlowrootWebMvcConfigurer(
            RustGlowrootInterceptor interceptor,
            Environment environment) {
        return new TelemetryWebMvcConfigurer(interceptor, resolveInterceptorOrder(environment));
    }

    private static int resolveInterceptorOrder(Environment environment) {
        Integer order = environment.getProperty("reactor.glowroot.spring.order", Integer.class);
        if (order == null) {
            order = environment.getProperty("reactor.glowroot.spring.interceptor-order", Integer.class);
        }
        if (order == null) {
            order = environment.getProperty("reactor.glowroot.spring.filter-order", Integer.class);
        }
        return order == null ? Integer.MIN_VALUE + 100 : order;
    }

    private record TelemetryWebMvcConfigurer(
            RustGlowrootInterceptor interceptor,
            int order) implements WebMvcConfigurer {

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).order(order);
        }
    }
}
