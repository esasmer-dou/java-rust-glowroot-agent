package com.reactor.glowroot.benchmark;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** Real non-web Spring Boot process used to guard the native core auto-configuration. */
@SpringBootApplication
public class NonWebGlowrootBenchmarkApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(NonWebGlowrootBenchmarkApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            NativeTelemetry telemetry = context.getBean(NativeTelemetry.class);
            NativeTelemetry.SqlStatement statement = telemetry.sqlStatement(
                    "invoice.load",
                    "select id, status from invoice where id = ?"
            );
            long startedAt = statement.start();
            statement.recordSuccess(startedAt, 1L);
            String diagnostics = telemetry.diagnosticsJson();
            if (!diagnostics.contains("\"enabled\":true")
                    || !diagnostics.contains("\"active_profile\":\"full\"")
                    || !diagnostics.contains("\"jvm_probe_registered\":true")
                    || !diagnostics.contains("\"registered_sql\":1")) {
                throw new IllegalStateException("Non-web native telemetry did not start: " + diagnostics);
            }
            if (context.containsBean("rustGlowrootInterceptor")
                    || context.containsBean("rustGlowrootWebMvcConfigurer")) {
                throw new IllegalStateException("MVC telemetry beans must not exist in a non-web application");
            }
            System.out.println("NON_WEB_GLOWROOT_READY " + diagnostics);
        }
    }
}
