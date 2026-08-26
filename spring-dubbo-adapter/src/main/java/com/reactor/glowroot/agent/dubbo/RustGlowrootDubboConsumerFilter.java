package com.reactor.glowroot.agent.dubbo;

import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;

/** Diagnostic-only Dubbo consumer timing; payloads and RPC arguments are never captured. */
@Activate(group = CommonConstants.CONSUMER, order = Integer.MIN_VALUE + 1_000)
public final class RustGlowrootDubboConsumerFilter implements Filter, Filter.Listener {

    private static final String OBSERVATION_KEY =
            RustGlowrootDubboConsumerFilter.class.getName() + ".observation";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        HttpRequestTraceState.DubboObservation observation =
                HttpRequestTraceState.beginDubbo(
                        invocation.getServiceName(),
                        invocation.getMethodName());
        if (observation == null) return invoker.invoke(invocation);
        invocation.put(OBSERVATION_KEY, observation);
        try {
            return invoker.invoke(invocation);
        } catch (RuntimeException | Error error) {
            observation.complete(true);
            throw error;
        }
    }

    @Override
    public void onResponse(Result result, Invoker<?> invoker, Invocation invocation) {
        complete(invocation, result != null && result.hasException());
    }

    @Override
    public void onError(Throwable error, Invoker<?> invoker, Invocation invocation) {
        complete(invocation, true);
    }

    private static void complete(Invocation invocation, boolean error) {
        Object value = invocation.get(OBSERVATION_KEY);
        if (value instanceof HttpRequestTraceState.DubboObservation observation) {
            observation.complete(error);
        }
    }
}
