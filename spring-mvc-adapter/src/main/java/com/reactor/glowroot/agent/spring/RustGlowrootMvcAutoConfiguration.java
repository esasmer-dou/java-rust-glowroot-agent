package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTelemetryAdapter;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
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

/** Optional Servlet MVC edge for the web-independent native telemetry runtime. */
@AutoConfiguration(
        after = RustGlowrootSpringAutoConfiguration.class,
        afterName = {
                "com.reactor.glowroot.agent.tomcat.RustGlowrootTomcatAutoConfiguration",
                "com.reactor.glowroot.agent.jetty.RustGlowrootJettyAutoConfiguration",
                "com.reactor.glowroot.agent.undertow.RustGlowrootUndertowAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({AsyncHandlerInterceptor.class, DispatcherServlet.class})
@ConditionalOnBean(NativeTelemetry.class)
@ConditionalOnProperty(
        prefix = "reactor.glowroot.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RustGlowrootMvcAutoConfiguration {

    /** Creates the optional Servlet MVC adapter configuration. */
    public RustGlowrootMvcAutoConfiguration() {}

    @Bean
    @ConditionalOnMissingBean(
            value = {RustGlowrootInterceptor.class, HttpTelemetryAdapter.class},
            name = "rustGlowrootFilterRegistration")
    RustGlowrootInterceptor rustGlowrootInterceptor(
            NativeTelemetry telemetry,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        return new RustGlowrootInterceptor(telemetry, config);
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootMvcTelemetryAdapter() {
        return new HttpTelemetryAdapter("mvc-interceptor");
    }

    @Bean
    @ConditionalOnBean(RustGlowrootInterceptor.class)
    @ConditionalOnMissingBean(name = {
            "rustGlowrootWebMvcConfigurer",
            "rustGlowrootFilterRegistration"
    })
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
