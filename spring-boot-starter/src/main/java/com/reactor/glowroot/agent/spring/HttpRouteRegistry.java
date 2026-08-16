package com.reactor.glowroot.agent.spring;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/** Fixed-capacity HTTP route table with allocation-free successful lookups. */
final class HttpRouteRegistry {

    static final int DISABLED_SLOT = -1;

    private static final VarHandle STRING_ELEMENT =
            MethodHandles.arrayElementVarHandle(String[].class);

    private final int maxRoutes;
    private final int mask;
    private final String[] methods;
    private final String[] routes;
    private final int[] slots;
    private int size;

    HttpRouteRegistry(int maxRoutes) {
        int capacity = 2;
        while (capacity < maxRoutes * 2) capacity <<= 1;
        this.maxRoutes = maxRoutes;
        this.mask = capacity - 1;
        this.methods = new String[capacity];
        this.routes = new String[capacity];
        this.slots = new int[capacity];
    }

    int getOrRegister(String method, String route, HttpTelemetryRecorder telemetry) {
        int registered = find(method, route);
        if (registered != DISABLED_SLOT) return registered;
        return register(method, route, telemetry);
    }

    private int find(String method, String route) {
        int index = spreadHash(method, route) & mask;
        String registeredMethod;
        while ((registeredMethod = (String) STRING_ELEMENT.getAcquire(methods, index)) != null) {
            if (registeredMethod.equals(method) && routes[index].equals(route)) {
                return slots[index];
            }
            index = (index + 1) & mask;
        }
        return DISABLED_SLOT;
    }

    private synchronized int register(
            String method,
            String route,
            HttpTelemetryRecorder telemetry) {
        int registered = find(method, route);
        if (registered != DISABLED_SLOT) return registered;
        if (size >= maxRoutes) return DISABLED_SLOT;

        int index = spreadHash(method, route) & mask;
        while ((String) STRING_ELEMENT.getAcquire(methods, index) != null) {
            index = (index + 1) & mask;
        }
        int slot = telemetry.registerHttpRoute(method, route);
        if (slot == DISABLED_SLOT) return DISABLED_SLOT;
        routes[index] = route;
        slots[index] = slot;
        STRING_ELEMENT.setRelease(methods, index, method);
        size++;
        return slot;
    }

    private static int spreadHash(String method, String route) {
        int hash = 31 * method.hashCode() + route.hashCode();
        return hash ^ (hash >>> 16);
    }
}
