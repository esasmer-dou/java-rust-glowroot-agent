package com.reactor.glowroot.agent.spring;

interface HttpTelemetryRecorder {
    int registerHttpRoute(String method, String route);

    void recordHttp(int slot, int status, long durationNanos, int sampleWeight);
}
