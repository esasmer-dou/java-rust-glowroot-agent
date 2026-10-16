package com.reactor.glowroot.agent.runtime;

import java.util.concurrent.ConcurrentHashMap;

/** A bounded two-level lookup: no composite key allocation on repeated service/method calls. */
public final class OperationRegistry {
    private final OperationTelemetrySink sink;
    private final String type;
    private final int capacity;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, NativeOperation>> services =
            new ConcurrentHashMap<>();
    private volatile int size;

    public OperationRegistry(OperationTelemetrySink sink, String type, int capacity) {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("capacity must be 1..64");
        this.sink = sink;
        this.type = type;
        this.capacity = capacity;
    }

    public NativeOperation operation(String service, String method) {
        if (sink == null || service == null || method == null) return NativeOperation.NOOP;
        var methods = services.get(service);
        NativeOperation found = methods == null ? null : methods.get(method);
        if (found != null) return found;
        return size >= capacity ? NativeOperation.NOOP : register(service, method);
    }

    private synchronized NativeOperation register(String service, String method) {
        var methods = services.get(service);
        if (methods != null && methods.containsKey(method)) return methods.get(method);
        if (size >= capacity || service.length() + method.length() > 254) return NativeOperation.NOOP;
        NativeOperation operation = NativeOperation.register(sink, type, service + "#" + method);
        services.computeIfAbsent(service, ignored -> new ConcurrentHashMap<>()).put(method, operation);
        size++;
        return operation;
    }
}
