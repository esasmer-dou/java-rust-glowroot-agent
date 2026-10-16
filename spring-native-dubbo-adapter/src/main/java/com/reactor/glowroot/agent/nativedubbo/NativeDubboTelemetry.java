package com.reactor.glowroot.agent.nativedubbo;

import com.reactor.glowroot.agent.runtime.NativeOperation;
import com.reactor.glowroot.agent.runtime.OperationTelemetrySink;
import com.reactor.rust.dubbo.runtime.DubboProviderTelemetry;

/** Each generated method receives a fixed descriptor at startup; no invocation map or reflection. */
public final class NativeDubboTelemetry implements DubboProviderTelemetry {
    private final OperationTelemetrySink sink;

    public NativeDubboTelemetry(OperationTelemetrySink sink) { this.sink = sink; }

    @Override public Method method(String service, String method) {
        NativeOperation operation = NativeOperation.register(sink, "Dubbo Provider", service + "#" + method);
        if (!operation.isEnabled()) return Method.NONE;
        return new Method() {
            public long start() { return operation.start(); }
            public void complete(long startedAt, Throwable error) {
                if (error == null) operation.success(startedAt); else operation.failure(startedAt, error);
            }
        };
    }
}
