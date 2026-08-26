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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

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

    @Bean
    @ConditionalOnMissingBean
    RustGlowrootMvcEnricher rustGlowrootMvcEnricher(NativeTelemetry telemetry) {
        return new RustGlowrootMvcEnricher(telemetry);
    }

    @Bean(name = "rustGlowrootHttpTelemetryAdapter")
    @ConditionalOnMissingBean(HttpTelemetryAdapter.class)
    HttpTelemetryAdapter rustGlowrootMvcTelemetryAdapter() {
        return new HttpTelemetryAdapter("mvc-interceptor");
    }

    @Bean
    @ConditionalOnMissingBean(name = {
            "rustGlowrootWebMvcConfigurer",
            "rustGlowrootFilterRegistration"
    })
    WebMvcConfigurer rustGlowrootWebMvcConfigurer(
            ObjectProvider<RustGlowrootInterceptor> interceptor,
            RustGlowrootMvcEnricher enricher,
            Environment environment) {
        TelemetryConfig config = TelemetryConfig.from(environment::getProperty);
        boolean detailEnabled = config.traceCapacity() > 0
                || (config.profile().errorDetailsEnabled() && config.errorTraceCapacity() > 0);
        return new TelemetryWebMvcConfigurer(
                interceptor.getIfAvailable(),
                enricher,
                detailEnabled,
                resolveInterceptorOrder(environment));
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
            RustGlowrootMvcEnricher enricher,
            boolean detailEnabled,
            int order) implements WebMvcConfigurer {

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            if (detailEnabled) {
                registry.addInterceptor(enricher).order(order);
            }
            if (interceptor != null) {
                registry.addInterceptor(interceptor).order(order + 1);
            }
        }

        @Override
        public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
            if (detailEnabled && !resolvers.contains(enricher)) {
                resolvers.add(0, enricher);
            }
        }
    }
}
