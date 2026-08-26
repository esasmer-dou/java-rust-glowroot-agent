package com.reactor.glowroot.agent.dubbo;

import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustGlowrootDubboConsumerFilterTest {

    @AfterEach
    void disable() {
        HttpRequestTraceState.setEnabled(false);
        HttpRequestTraceState.setCurrentLookup(null);
    }

    @Test
    void isDiscoverableThroughTheDubboSpiUsedByTheTargetRuntime() {
        assertTrue(ExtensionLoader.getExtensionLoader(org.apache.dubbo.rpc.Filter.class)
                .getSupportedExtensions()
                .contains("rust-glowroot"));
    }

    @Test
    void recordsOneBoundedConsumerCallForTheActiveHttpRequest() {
        HttpRequestTraceState.setEnabled(true);
        HttpRequestTraceState state = HttpRequestTraceState.open();
        HttpRequestTraceState.setCurrentLookup(() -> state);
        state.beginController();
        Invocation invocation = invocation("com.example.CatalogService", "list");
        Result result = result(false);
        Invoker<?> invoker = invoker(result);
        RustGlowrootDubboConsumerFilter filter = new RustGlowrootDubboConsumerFilter();

        assertSame(result, filter.invoke(invoker, invocation));
        filter.onResponse(result, invoker, invocation);
        state.completeController();

        HttpRequestTraceState.Snapshot snapshot = state.snapshot(10_000_000L);
        assertEquals("com.example.CatalogService#list()", snapshot.dubboOperation());
        assertEquals(1L, snapshot.dubboCount());
        assertEquals(0L, snapshot.dubboErrors());
    }

    private static Invocation invocation(String service, String method) {
        Map<Object, Object> attributes = new HashMap<>();
        return (Invocation) Proxy.newProxyInstance(
                Invocation.class.getClassLoader(),
                new Class<?>[] {Invocation.class},
                (proxy, called, arguments) -> switch (called.getName()) {
                    case "getServiceName" -> service;
                    case "getMethodName" -> method;
                    case "put" -> attributes.put(arguments[0], arguments[1]);
                    case "get" -> attributes.get(arguments[0]);
                    case "getAttributes" -> attributes;
                    case "toString" -> service + "#" + method;
                    default -> defaultValue(called.getReturnType());
                });
    }

    private static Result result(boolean error) {
        return (Result) Proxy.newProxyInstance(
                Result.class.getClassLoader(),
                new Class<?>[] {Result.class},
                (proxy, called, arguments) -> switch (called.getName()) {
                    case "hasException" -> error;
                    case "toString" -> "result";
                    default -> defaultValue(called.getReturnType());
                });
    }

    private static Invoker<?> invoker(Result result) {
        return (Invoker<?>) Proxy.newProxyInstance(
                Invoker.class.getClassLoader(),
                new Class<?>[] {Invoker.class},
                (proxy, called, arguments) -> switch (called.getName()) {
                    case "invoke" -> result;
                    case "isAvailable" -> true;
                    case "toString" -> "invoker";
                    default -> defaultValue(called.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
