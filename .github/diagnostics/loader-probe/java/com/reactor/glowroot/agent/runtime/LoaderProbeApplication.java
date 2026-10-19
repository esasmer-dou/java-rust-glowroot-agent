package com.reactor.glowroot.agent.runtime;

import org.springframework.boot.SpringApplication;
import com.reactor.glowroot.benchmark.SpringGlowrootBenchmarkApplication;

/** Diagnostic only: keep the application and flags identical, vary just native loading order. */
public final class LoaderProbeApplication {
    public static void main(String[] args) {
        if (Boolean.getBoolean("probe.preload")) NativeLibraryLoader.load();
        SpringApplication.run(SpringGlowrootBenchmarkApplication.class, args);
    }
}
