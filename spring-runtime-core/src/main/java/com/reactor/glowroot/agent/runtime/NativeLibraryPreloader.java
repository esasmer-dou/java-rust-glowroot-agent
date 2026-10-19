package com.reactor.glowroot.agent.runtime;

import java.util.function.Consumer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Loads the verified native image after configuration, before the Spring bean graph is built. */
public final class NativeLibraryPreloader implements EnvironmentPostProcessor, Ordered {
    private final Consumer<ConfigurableEnvironment> load;

    /** Creates the opt-in native preloader; it does not start an engine or exporter thread. */
    public NativeLibraryPreloader() {
        this(environment -> NativeLibraryLoader.load(environment::getProperty));
    }

    NativeLibraryPreloader(Consumer<ConfigurableEnvironment> load) {
        this.load = load;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty("reactor.glowroot.enabled", Boolean.class, false)) {
            load.accept(environment);
        }
    }
}
