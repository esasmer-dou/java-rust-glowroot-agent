package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.runtime.NativeTelemetry;

/** Thin Java edge over the process-scoped Rust telemetry runtime. */
record NativeHttpTelemetryRecorder(NativeTelemetry telemetry) implements HttpTelemetryRecorder {

    @Override
    public int registerHttpRoute(String method, String route) {
        return telemetry.registerHttpRoute(method, route);
    }

    @Override
    public void recordHttp(int slot, int status, long durationNanos, int sampleWeight) {
        telemetry.recordHttp(slot, status, durationNanos, sampleWeight);
    }

    @Override
    public void recordError(int slot, long durationNanos, Throwable error) {
        telemetry.recordError(slot, durationNanos, error);
    }
}
