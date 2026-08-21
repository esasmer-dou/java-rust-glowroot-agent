package com.reactor.glowroot.agent.http;

import java.util.Objects;

/** Identifies the single HTTP lifecycle adapter active in a Spring application. */
public record HttpTelemetryAdapter(String id) {

    public HttpTelemetryAdapter {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) throw new IllegalArgumentException("HTTP telemetry adapter id is blank");
        // This readiness signal must remain visible even when the application suppresses INFO logs.
        // It is emitted once at startup and never touches the request path.
        System.err.println(
                "Java-Rust Glowroot HTTP telemetry active: adapter=" + id
                        + ", transaction-type=Web");
    }
}
