package com.reactor.glowroot.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class NativeOperationTest {
    @Test void disabledDoesNotLoadNativeCodeOrAllocateWrappers() {
        Runnable task = () -> {};
        NativeOperation operation = NativeOperation.register(null, "Scheduler", "refresh");
        assertSame(NativeOperation.NOOP, operation);
        assertEquals(0, operation.start());
        assertSame(task, operation.wrap(task));
        task.run();
    }

    @Test void errorsAndSuccessesAreCompletedOnceAndOriginalFailureEscapes() {
        Sink sink = new Sink();
        NativeOperation operation = NativeOperation.register(sink, "Scheduler", "refresh");
        operation.wrap(() -> {}).run();
        var failure = new IllegalStateException("failed refresh");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> operation.wrap(() -> { throw failure; }).run()));
        assertEquals(2, sink.errors.size());
        assertNull(sink.errors.get(0));
        assertSame(failure, sink.errors.get(1));
    }

    @Test void methodRegistryIsBoundedAndReusesDescriptors() {
        Sink sink = new Sink();
        OperationRegistry registry = new OperationRegistry(sink, "Dubbo Provider", 2);
        var first = registry.operation("Catalog", "get");
        assertSame(first, registry.operation("Catalog", "get"));
        registry.operation("Catalog", "put");
        assertSame(NativeOperation.NOOP, registry.operation("Other", "get"));
        assertEquals(2, sink.registered);
    }

    @Test void asyncWorkUsesRealCompletionAndPreservesTheOriginalFuture() {
        Sink sink = new Sink();
        NativeOperation operation = NativeOperation.register(sink, "Background", "refresh");
        var future = new java.util.concurrent.CompletableFuture<String>();
        assertSame(future, operation.observe(operation.start(), future));
        assertTrue(sink.errors.isEmpty());
        var failure = new IllegalStateException("async failure");
        future.completeExceptionally(failure);
        future.complete("late");
        assertEquals(1, sink.errors.size());
        assertSame(failure, sink.errors.getFirst());
    }

    static final class Sink implements OperationTelemetrySink {
        int registered;
        final ArrayList<Throwable> errors = new ArrayList<>();
        @Override public int registerOperation(String type, String name) { return registered++; }
        @Override public void recordOperation(int slot, long duration, Throwable error) {
            assertTrue(duration >= 0);
            errors.add(error);
        }
    }
}
