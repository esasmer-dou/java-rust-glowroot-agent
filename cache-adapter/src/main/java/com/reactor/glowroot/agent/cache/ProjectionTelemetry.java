package com.reactor.glowroot.agent.cache;

import com.reactor.glowroot.agent.runtime.NativeOperation;
import com.reactor.glowroot.agent.runtime.StandaloneTelemetry;

import com.reactor.rust.cache.scheduler.ProjectionRefreshScheduler.ProjectionRefresher;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Optional cache writer adapter. Only loads when a cache application explicitly uses it. */
public final class ProjectionTelemetry {
    private ProjectionTelemetry() {}
    public static ProjectionRefresher wrap(StandaloneTelemetry telemetry, Set<String> projections,
                                            ProjectionRefresher refresher) {
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(refresher, "refresher");
        if (!telemetry.enabled()) return refresher;
        Objects.requireNonNull(projections, "projections");
        if (projections.isEmpty() || projections.size() > 64)
            throw new IllegalArgumentException("Register 1..64 fixed projection names");
        var operations = new HashMap<String, NativeOperation>();
        for (String projection : projections) {
            if (projection == null || projection.isBlank())
                throw new IllegalArgumentException("Projection names must not be blank");
            operations.put(projection, telemetry.operation("Scheduler", "cache-refresh:" + projection));
        }
        return observed(Map.copyOf(operations), refresher);
    }

    /** Manage the optional telemetry handle in the writer's existing shutdown lifecycle. */
    public static <R extends AutoCloseable> ProjectionWriterApplication.CacheWriterFactory writer(
            ProjectionWriterApplication.ManagedResourceFactory<R> resources,
            ProjectionWriterApplication.ManagedCacheWriterFactory<R> writers) {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(writers, "writers");
        return (context, cache, properties) -> {
            var telemetry = context.manage(StandaloneTelemetry.startIfEnabled(key -> properties.get(key, null)));
            R resource = context.manage(resources.create(properties));
            var refresher = Objects.requireNonNull(writers.create(resource, cache, properties), "refresher");
            if (!telemetry.enabled()) return refresher;
            var names = CacheWriterProjectionSettings.resolveAll(properties, context.rootPrefix()).stream()
                    .map(CacheWriterProjectionSettings::name).collect(Collectors.toSet());
            return wrap(telemetry, names, refresher);
        };
    }

    static ProjectionRefresher observed(Map<String, NativeOperation> operations, ProjectionRefresher refresher) {
        return (projection, lock, lease) -> {
            NativeOperation operation = operations.get(projection);
            if (operation == null) throw new IllegalArgumentException("Unregistered projection");
            long started = operation.start();
            try {
                var result = refresher.refresh(projection, lock, lease);
                operation.complete(started, result != null && (result.published() || result.skippedLocked()));
                return result;
            } catch (Exception | Error failure) {
                operation.failure(started, failure);
                throw failure;
            }
        };
    }
}
