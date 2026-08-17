package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTelemetrySink;
import com.reactor.glowroot.agent.runtime.NativeTelemetry;
import com.reactor.glowroot.agent.runtime.TelemetryConfig;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Compatibility adapter for applications that manually registered the public 0.2.0 filter.
 *
 * <p>The starter does not register this class. New applications use {@link RustGlowrootInterceptor}
 * through auto-configuration, which avoids wrapping the Servlet filter chain.</p>
 *
 * @deprecated use starter auto-configuration and {@link RustGlowrootInterceptor}
 */
@Deprecated(since = "0.2.1", forRemoval = false)
public final class RustGlowrootFilter implements Filter {

    private final RustGlowrootInterceptor delegate;

    /**
     * Creates the manual compatibility filter.
     *
     * @param telemetry process telemetry handle
     * @param config bounded telemetry configuration
     */
    public RustGlowrootFilter(NativeTelemetry telemetry, TelemetryConfig config) {
        this.delegate = new RustGlowrootInterceptor(telemetry, config);
    }

    RustGlowrootFilter(HttpTelemetrySink telemetry, TelemetryConfig config) {
        this.delegate = new RustGlowrootInterceptor(telemetry, config);
    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || httpRequest.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }

        delegate.preHandle(httpRequest, httpResponse, this);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            delegate.afterCompletion(httpRequest, httpResponse, this, exception);
            throw exception;
        } catch (Error error) {
            delegate.complete(httpRequest, httpResponse, error, 500);
            throw error;
        }

        if (!httpRequest.isAsyncStarted()) {
            delegate.afterCompletion(httpRequest, httpResponse, this, null);
            return;
        }

        delegate.afterConcurrentHandlingStarted(httpRequest, httpResponse, this);
        AsyncCompletion completion = new AsyncCompletion(httpRequest, httpResponse);
        try {
            httpRequest.getAsyncContext().addListener(completion);
        } catch (IllegalStateException completedBeforeRegistration) {
            completion.complete(null, 0);
        }
    }

    private final class AsyncCompletion implements AsyncListener {
        private static final VarHandle COMPLETED;

        static {
            try {
                COMPLETED = MethodHandles.lookup().findVarHandle(
                        AsyncCompletion.class,
                        "completed",
                        int.class);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private final HttpServletRequest request;
        private final HttpServletResponse response;
        @SuppressWarnings("unused")
        private volatile int completed;

        private AsyncCompletion(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete(null, 0);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            complete(new ServletException("Async request timed out"), 504);
        }

        @Override
        public void onError(AsyncEvent event) {
            complete(event.getThrowable(), 500);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }

        private void complete(Throwable exception, int forcedStatus) {
            if (COMPLETED.compareAndSet(this, 0, 1)) {
                delegate.completeAsync(request, response, exception, forcedStatus);
            }
        }
    }
}
