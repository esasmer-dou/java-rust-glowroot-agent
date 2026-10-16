package com.reactor.glowroot.agent.dubbo;

import com.reactor.glowroot.agent.runtime.NativeOperation;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.OperationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/** Top-level provider transactions, including asynchronous completions and business failures. */
@Activate(group = CommonConstants.PROVIDER, order = Integer.MIN_VALUE + 1_000)
public final class RustGlowrootDubboProviderFilter implements Filter, Filter.Listener {
    private static final String KEY = RustGlowrootDubboProviderFilter.class.getName();
    private volatile NativeTelemetry owner;
    private volatile OperationRegistry registry;
    private final boolean enabled = enabled();
    private final OperationRegistry suppliedRegistry;

    public RustGlowrootDubboProviderFilter() { this(null); }
    public RustGlowrootDubboProviderFilter(OperationRegistry registry) { this.suppliedRegistry = registry; }

    @Override public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        NativeTelemetry telemetry = NativeTelemetry.current();
        if ((telemetry == null && suppliedRegistry == null) || !enabled
                || (telemetry != null && !telemetry.providerObservationEnabled())) {
            return invoker.invoke(invocation);
        }
        NativeOperation operation = (suppliedRegistry == null ? registry(telemetry) : suppliedRegistry)
                .operation(invocation.getServiceName(), invocation.getMethodName());
        if (!operation.isEnabled()) return invoker.invoke(invocation);
        Observation observation = new Observation(operation);
        invocation.put(KEY, observation);
        try {
            return invoker.invoke(invocation);
        } catch (RuntimeException | Error error) {
            observation.complete(error);
            throw error;
        }
    }

    private static boolean enabled() {
        String value = System.getProperty("reactor.glowroot.dubbo.provider.enabled");
        if (value == null) value = System.getenv("REACTOR_GLOWROOT_DUBBO_PROVIDER_ENABLED");
        return value == null || Boolean.parseBoolean(value);
    }

    private OperationRegistry registry(NativeTelemetry telemetry) {
        if (owner == telemetry) return registry;
        synchronized (this) {
            if (owner != telemetry) {
                registry = new OperationRegistry(telemetry, "Dubbo Provider", telemetry.maxRoutes());
                owner = telemetry;
            }
            return registry;
        }
    }

    @Override public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        complete(invocation, result == null ? null : result.getException());
    }
    @Override public void onError(Throwable error, Invoker<?> invoker, Invocation invocation) {
        complete(invocation, error);
    }
    private static void complete(Invocation invocation, Throwable error) {
        if (invocation.get(KEY) instanceof Observation observation) observation.complete(error);
    }

    private static final class Observation extends AtomicBoolean {
        private static final long serialVersionUID = 1L;
        private final NativeOperation operation;
        private final long startedAt;
        Observation(NativeOperation operation) {
            this.operation = operation;
            this.startedAt = operation.start();
        }
        void complete(Throwable error) {
            if (!compareAndSet(false, true)) return;
            if (error == null) operation.success(startedAt); else operation.failure(startedAt, error);
        }
    }
}
