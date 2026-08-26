package com.reactor.glowroot.agent.spring;

import com.reactor.glowroot.agent.http.HttpTraceMetadata;
import com.reactor.glowroot.agent.http.HttpRequestTraceState;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustGlowrootMvcEnricherTest {

    @Test
    void capturesControllerAndExceptionWithoutConsumingTheApplicationError() throws Exception {
        HttpRequestTraceState.setEnabled(true);
        try {
            RustGlowrootMvcEnricher enricher = new RustGlowrootMvcEnricher(true);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
            MockHttpServletResponse response = new MockHttpServletResponse();
            HandlerMethod handler = new HandlerMethod(new SampleController(), "create");

            assertTrue(enricher.preHandle(request, response, handler));
            assertEquals(
                    SampleController.class.getName() + ".create()",
                    request.getAttribute(HttpTraceMetadata.CONTROLLER_ATTRIBUTE));
            assertTrue(request.getAttribute(HttpTraceMetadata.THREAD_STATS_ATTRIBUTE) instanceof long[]);
            assertTrue(request.getAttribute(HttpTraceMetadata.REQUEST_TRACE_STATE_ATTRIBUTE)
                    instanceof HttpRequestTraceState);

            IllegalStateException failure = new IllegalStateException("provider timeout");
            assertNull(enricher.resolveException(request, response, handler, failure));
            assertSame(failure, request.getAttribute(HttpTraceMetadata.ERROR_ATTRIBUTE));
            enricher.afterCompletion(request, response, handler, failure);
            assertNull(HttpRequestTraceState.current());
        } finally {
            HttpRequestTraceState.setEnabled(false);
        }
    }

    static final class SampleController {
        public void create() {}
    }
}
