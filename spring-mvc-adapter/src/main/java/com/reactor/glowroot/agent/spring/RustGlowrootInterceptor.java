package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.BoundedHttpTelemetry;
import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** Low-allocation Spring MVC request timing adapter. */
public final class RustGlowrootInterceptor implements AsyncHandlerInterceptor {

    private static final String OBSERVATION_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".observation";
    private static final String COMPLETED_ATTRIBUTE =
            RustGlowrootInterceptor.class.getName() + ".completed";
    private static final Object UNSAMPLED_ASYNC = new Object();
    private static final Object COMPLETED = new Object();

    private final BoundedHttpTelemetry telemetry;

    /**
     * Creates the low-allocation MVC interceptor.
     *
     * @param telemetry process telemetry handle
     * @param config bounded telemetry configuration
     */
    public RustGlowrootInterceptor(NativeTelemetry telemetry, TelemetryConfig config) {
        this((HttpTelemetrySink) telemetry, config);
    }

    RustGlowrootInterceptor(HttpTelemetrySink telemetry, TelemetryConfig config) {
        this.telemetry = new BoundedHttpTelemetry(telemetry, config);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        DispatcherType dispatcherType = request.getDispatcherType();
        if (dispatcherType == DispatcherType.ERROR
                && request.getAttribute(COMPLETED_ATTRIBUTE) != null) {
            return true;
        }
        if (dispatcherType != DispatcherType.REQUEST && dispatcherType != DispatcherType.ERROR) {
            return true;
        }

        telemetry.beginThreadObservation();
        return true;
    }

    @Override
    public void afterConcurrentHandlingStarted(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        long token = telemetry.takeThreadObservation();
        request.setAttribute(
                OBSERVATION_ATTRIBUTE,
                token == 0L ? UNSAMPLED_ASYNC : new Observation(token));
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        complete(request, response, exception, 0);
    }

    void complete(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable exception,
            int forcedStatus) {
        DispatcherType dispatcherType = request.getDispatcherType();
        if (dispatcherType == DispatcherType.ASYNC) {
            completeAsync(request, response, exception, forcedStatus);
            return;
        }
        if (dispatcherType == DispatcherType.ERROR
                && request.getAttribute(COMPLETED_ATTRIBUTE) != null) {
            return;
        }
        if (dispatcherType != DispatcherType.REQUEST && dispatcherType != DispatcherType.ERROR) {
            return;
        }

        long token = telemetry.takeThreadObservation();
        int observationFlags = telemetry.observationFlags(token);
        Throwable error = exception;
        int status = forcedStatus == 0
                ? responseStatus(request, response, dispatcherType)
                : forcedStatus;
        if (dispatcherType == DispatcherType.ERROR && error == null) {
            Object dispatchedError = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            if (dispatchedError instanceof Throwable throwable) error = throwable;
        }
        if (error != null && status < 500) status = 500;
        if (status >= 400 || error != null) request.setAttribute(COMPLETED_ATTRIBUTE, COMPLETED);

        // Successful unsampled requests are the dominant path. Return before reading the timer,
        // resolving route metadata, or touching native state.
        if (observationFlags == 0) {
            if (status >= 500 || error != null) {
                recordStatus(request, status, 0L, 0, error, dispatcherType);
            }
            return;
        }

        long durationNanos = telemetry.elapsedNanos(token);
        recordStatus(request, status, durationNanos, observationFlags, error, dispatcherType);
    }

    void completeAsync(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable exception,
            int forcedStatus) {
        Object observed = request.getAttribute(OBSERVATION_ATTRIBUTE);
        if (observed == null) return;
        request.removeAttribute(OBSERVATION_ATTRIBUTE);

        int status = forcedStatus == 0 ? response.getStatus() : forcedStatus;
        if (exception != null && status < 500) status = 500;
        if (status >= 400 || exception != null) request.setAttribute(COMPLETED_ATTRIBUTE, COMPLETED);
        if (observed instanceof Observation observation) {
            long durationNanos = telemetry.elapsedNanos(observation.token());
            recordStatus(
                    request,
                    status,
                    durationNanos,
                    telemetry.observationFlags(observation.token()),
                    exception,
                    DispatcherType.ASYNC);
            return;
        }
        if (status >= 500) {
            recordStatus(request, status, 0L, 0, exception, DispatcherType.ASYNC);
        }
    }

    private void recordStatus(
            HttpServletRequest request,
            int status,
            long durationNanos,
            int observation,
            Throwable error,
            DispatcherType dispatcherType) {
        if (!telemetry.shouldRecord(observation, status, durationNanos, error)) return;

        String method = request.getMethod();
        Object matched = dispatcherType == DispatcherType.ERROR && status == 404
                ? null
                : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        telemetry.record(observation, method, matched, status, durationNanos, error);
    }

    private static int responseStatus(
            HttpServletRequest request,
            HttpServletResponse response,
            DispatcherType dispatcherType) {
        if (dispatcherType == DispatcherType.ERROR) {
            Object errorStatus = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
            if (errorStatus instanceof Number number) return number.intValue();
        }
        return response.getStatus();
    }

    private record Observation(long token) {}
}
