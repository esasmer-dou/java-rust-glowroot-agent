package com.reactor.glowroot.agent.runtime;

import java.util.Locale;

/** Bounded telemetry surfaces that can be changed at runtime. */
public enum TelemetryProfile {
    MICRO(0),
    JVM(1),
    SQL((1 << 1) | (1 << 2)),
    FULL(1 | (1 << 1) | (1 << 2)),
    DIAGNOSTIC(1 | (1 << 1) | (1 << 2) | (1 << 3));

    private final int featureMask;

    TelemetryProfile(int featureMask) {
        this.featureMask = featureMask;
    }

    int featureMask() {
        return featureMask;
    }

    /** Value accepted by {@code reactor.glowroot.profile}. */
    public String propertyValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TelemetryProfile parse(String value) {
        if (value == null || value.isBlank()) return MICRO;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Unsupported reactor.glowroot.profile '" + value
                            + "'. Use micro, jvm, sql, full, or diagnostic.",
                    error
            );
        }
    }
}
