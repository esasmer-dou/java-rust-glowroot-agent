package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpThreadStats;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Adds bounded Spring controller and exception metadata without owning request timing. */
final class RustGlowrootMvcEnricher
        implements AsyncHandlerInterceptor, HandlerExceptionResolver, Ordered {

    private static final int MAX_CONTROLLER_DESCRIPTORS = 512;
    private static final String CONTROLLER_LIMIT_EXCEEDED = "<controller-limit-exceeded>";

    private final ConcurrentHashMap<Method, String> descriptors = new ConcurrentHashMap<>();
    private final BooleanSupplier diagnosticsEnabled;

    RustGlowrootMvcEnricher() {
        this(false);
    }

    RustGlowrootMvcEnricher(boolean threadStatsEnabled) {
        this(() -> threadStatsEnabled);
    }

    RustGlowrootMvcEnricher(NativeTelemetry telemetry) {
        this(() -> telemetry.activeProfile().diagnosticsEnabled());
    }

    private RustGlowrootMvcEnricher(BooleanSupplier diagnosticsEnabled) {
        this.diagnosticsEnabled = diagnosticsEnabled;
        HttpRequestTraceState.setCurrentLookup(RustGlowrootMvcEnricher::currentRequestTrace);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        boolean diagnostic = diagnosticsEnabled.getAsBoolean();
        if (diagnostic
                && request.getAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE) == null) {
            request.setAttribute(
                    HttpTraceMetadata.THREAD_STATS_ATTRIBUTE,
                    HttpThreadStats.begin(true));
        }
        if (request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE) == null) {
            HttpRequestTraceState state = HttpRequestTraceState.open();
            if (state != null) {
                state.beginController();
                request.setAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE, state);
            }
        }
        if (handler instanceof HandlerMethod handlerMethod) {
            request.setAttribute(
                    HttpTraceMetadata.CONTROLLER_ATTRIBUTE,
                    descriptor(handlerMethod));
        }
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            @Nullable Exception exception) {
        if (exception != null) {
            request.setAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE, exception);
        }
        completeRequestTrace(request);
    }

    @Override
    public @Nullable ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            @Nullable Object handler,
            Exception exception) {
        // Returning null preserves the application's existing exception resolver chain.
        request.setAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE, exception);
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String descriptor(HandlerMethod handler) {
        Method method = handler.getMethod();
        String cached = descriptors.get(method);
        if (cached != null) return cached;
        if (descriptors.size() >= MAX_CONTROLLER_DESCRIPTORS) {
            return CONTROLLER_LIMIT_EXCEEDED;
        }
        String created = handler.getBeanType().getName() + "." + method.getName() + "()";
        String prior = descriptors.putIfAbsent(method, created);
        return prior == null ? created : prior;
    }

    private static void completeRequestTrace(HttpServletRequest request) {
        Object state = request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE);
        if (state instanceof HttpRequestTraceState traceState) {
            traceState.completeController();
        }
    }

    private static HttpRequestTraceState currentRequestTrace() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) return null;
        Object value = attributes.getAttribute(
                HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        return value instanceof HttpRequestTraceState state ? state : null;
    }
}
