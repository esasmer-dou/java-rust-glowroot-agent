package com.reactor.glowroot.agent.runtime;

/** Fixed-name business boundaries. Implementations must not retain request arguments. */
public interface OperationTelemetrySink {
    int registerOperation(String transactionType, String name);
    void recordOperation(int slot, long durationNanos, Throwable error);
}
