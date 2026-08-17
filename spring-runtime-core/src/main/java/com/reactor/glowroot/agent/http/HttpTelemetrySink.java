package com.reactor.glowroot.agent.http;

/** Minimal Java-to-Rust HTTP telemetry boundary. */
public interface HttpTelemetrySink {

    int registerHttpRoute(String method, String route);

    void recordHttp(int slot, int status, long durationNanos, int sampleWeight);

    default void recordError(int slot, long durationNanos, Throwable error) {}
}
