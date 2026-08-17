package com.reactor.glowroot.agent.http;

import java.util.Objects;

/** Identifies the single HTTP lifecycle adapter active in a Spring application. */
public record HttpTelemetryAdapter(String id) {

    public HttpTelemetryAdapter {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("HTTP telemetry adapter id is blank");
    }
}
