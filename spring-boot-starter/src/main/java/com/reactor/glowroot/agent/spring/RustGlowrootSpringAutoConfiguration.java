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
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Opt-in Spring MVC integration. No transformer, scanner, or extra executor is installed. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, WebMvcConfigurer.class})
@ConditionalOnProperty(prefix = "reactor.glowroot", name = "enabled", havingValue = "true")
public class RustGlowrootSpringAutoConfiguration {

    /** Creates the opt-in Spring MVC adapter configuration. */
    public RustGlowrootSpringAutoConfiguration() {}

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
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
    RustGlowrootInterceptor rustGlowrootInterceptor(
            NativeTelemetry telemetry,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        return new RustGlowrootInterceptor(telemetry, config);
    }

    @Bean(name = "rustGlowrootMvcConfigurer")
    @ConditionalOnMissingBean(name = "rustGlowrootMvcConfigurer")
    WebMvcConfigurer rustGlowrootMvcConfigurer(
            RustGlowrootInterceptor interceptor,
            Environment environment) {
        int order = environment.getProperty(
                "reactor.glowroot.spring.interceptor-order",
                Integer.class,
                Integer.MIN_VALUE + 100
        );
        return new OrderedInterceptorConfigurer(interceptor, order);
    }

    private record OrderedInterceptorConfigurer(
            RustGlowrootInterceptor interceptor,
            int order) implements WebMvcConfigurer {

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).order(order);
        }
    }
}
