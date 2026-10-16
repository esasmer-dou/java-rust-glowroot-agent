package com.reactor.glowroot.agent.nativedubbo;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.spring.RustGlowrootSpringAutoConfiguration;
import com.reactor.rust.dubbo.runtime.DubboProviderTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = RustGlowrootSpringAutoConfiguration.class,
        beforeName = "com.reactor.rust.dubbo.spring.RustDubboAutoConfiguration")
@ConditionalOnClass(DubboProviderTelemetry.class)
@ConditionalOnProperty(prefix = "reactor.glowroot", name = "enabled", havingValue = "true")
public class RustGlowrootNativeDubboAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DubboProviderTelemetry.class)
    @ConditionalOnProperty(prefix = "reactor.glowroot.dubbo.provider", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    DubboProviderTelemetry rustGlowrootProviderTelemetry(NativeTelemetry telemetry) {
        return new NativeDubboTelemetry(telemetry);
    }
}
