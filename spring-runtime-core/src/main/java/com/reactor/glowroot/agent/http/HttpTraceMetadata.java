package com.reactor.glowroot.agent.http;

/** Shared request attributes used to enrich a server-owned HTTP completion. */
public final class HttpTraceMetadata {

    public static final String CONTROLLER_ATTRIBUTE =
            HttpTraceMetadata.class.getName() + ".controller";
    public static final String ERROR_ATTRIBUTE =
            HttpTraceMetadata.class.getName() + ".error";
    public static final String THREAD_STATS_ATTRIBUTE =
            HttpTraceMetadata.class.getName() + ".thread-stats";
    public static final String REQUEST_TRACE_STATE_ATTRIBUTE =
            HttpTraceMetadata.class.getName() + ".request-trace-state";

    private HttpTraceMetadata() {}
}
