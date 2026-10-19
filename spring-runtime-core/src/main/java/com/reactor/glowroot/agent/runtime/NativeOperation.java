package com.reactor.glowroot.agent.runtime;

/** Register once, then time synchronous work using a primitive token without per-call allocation. */
public final class NativeOperation {
    private static final class FailureMarker {
        private static final Throwable INSTANCE = new BusinessFailure();
    }
    private static final class BusinessFailure extends RuntimeException {
        BusinessFailure() { super("Business operation reported failure", null, false, false); }
    }
    public static final NativeOperation NOOP = new NativeOperation(null, -1);
    private final OperationTelemetrySink sink;
    private final int slot;

    private NativeOperation(OperationTelemetrySink sink, int slot) {
        this.sink = sink;
        this.slot = slot;
    }

    public static NativeOperation register(OperationTelemetrySink sink, String type, String name) {
        if (sink == null) return NOOP;
        int slot = sink.registerOperation(type, name);
        return slot < 0 ? NOOP : new NativeOperation(sink, slot);
    }

    public long start() {
        return sink == null ? 0L : System.nanoTime();
    }

    public boolean isEnabled() { return sink != null; }

    public void success(long startedAt) {
        complete(startedAt, null);
    }

    public void failure(long startedAt, Throwable error) {
        complete(startedAt, java.util.Objects.requireNonNull(error, "error"));
    }

    /** Count a rejected business result without allocating an exception per call. */
    public void complete(long startedAt, boolean successful) {
        if (sink != null) complete(startedAt, successful ? null : FailureMarker.INSTANCE);
    }

    private void complete(long startedAt, Throwable error) {
        if (sink != null) sink.recordOperation(slot, Math.max(0L, System.nanoTime() - startedAt), error);
    }

    public Runnable wrap(Runnable task) {
        if (sink == null) return task;
        return () -> {
            long startedAt = start();
            try {
                task.run();
            } catch (RuntimeException | Error error) {
                failure(startedAt, error);
                throw error;
            }
            success(startedAt);
        };
    }

    /** Observe actual async completion without replacing the caller's future or adding an executor. */
    public <T> java.util.concurrent.CompletionStage<T> observe(
            long startedAt, java.util.concurrent.CompletionStage<T> stage) {
        java.util.Objects.requireNonNull(stage, "stage");
        if (sink != null) stage.whenComplete((value, error) -> complete(startedAt, error));
        return stage;
    }
}
