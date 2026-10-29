# Plain Java Operations

[English](functional-operations.md) | [Turkish](functional-operations.tr.md)

Use the matching `0.7.1` modules. Before deployment, check the [qualification status](releases/0.7.1.md#qualification-status) and published stable release.
No Spring server is required. Native ABI remains 6.
Use the `java-rust-glowroot-spring-runtime` artifact; Spring dependencies are provided,
not mandatory transitives for this explicit API.

```java
try (var telemetry = StandaloneTelemetry.startIfEnabled()) {
    var operation = telemetry.operation("Scheduler", "customer-refresh");
    long started = operation.start();
    try {
        boolean published = refreshCustomers();
        operation.complete(started, published);
    } catch (RuntimeException error) {
        operation.failure(started, error);
        throw error;
    }
}
```

Import `com.reactor.glowroot.agent.runtime.StandaloneTelemetry`.
Add `com.reactor:java-rust-glowroot-spring-runtime:0.7.1` to a plain Java application.
The artifact name is shared with Spring, but its Spring dependency is optional/provided.
This API does not start Spring or an HTTP server.

Keep the telemetry lifecycle open for the application's lifetime, not per request.
Register fixed names once. Never put a customer ID, SQL value or payload in a name.
`complete(started, false)` counts a business failure without creating a new exception.

| Setting | Effect |
| --- | --- |
| REACTOR_GLOWROOT_ENABLED=false | No native start; disabled operation is a no-op |
| REACTOR_GLOWROOT_ENABLED=true | Explicit lifecycle starts the existing Rust exporter |
| REACTOR_GLOWROOT_COLLECTOR_ADDRESS | Existing collector address |
| REACTOR_GLOWROOT_AGENT_ID | Unique application/replica identifier |

For cache writers, add the optional `java-rust-glowroot-cache-adapter` artifact.
It is not pulled into ordinary HTTP applications.
`com.reactor.glowroot.agent.cache.ProjectionTelemetry.wrap(telemetry, names, refresher)` wraps only
registered projections. Disabled mode returns the original refresher.
It adds no executor. This explicit adapter does not instrument arbitrary methods.
Native export, caps and profile lifecycle stay in the existing isolated Rust runtime.
Extra data still has a cost when enabled. Measure the chosen profile.

## Cache Writer Lifecycle

Add `com.reactor:java-rust-glowroot-cache-adapter:0.7.1` only to a writer that needs telemetry.
The application still adds `java-rust-cache:0.8.0` explicitly. The adapter does not pull Redis into HTTP-only applications.

```java
import com.reactor.glowroot.agent.cache.ProjectionTelemetry;
import com.reactor.rust.cache.scheduler.ProjectionWriterApplication;

ProjectionWriterApplication.runCache(
        "rest-sample-cache-writer.properties", "sample.writer",
        ProjectionTelemetry.writer(PostgresCustomerRepository::fromProperties, CustomerCacheMaterializer::new));
```

The writer owns startup and shutdown. Projection names come from its existing settings.
Disabled telemetry returns the original refresher. Enabled telemetry registers each fixed name once.
A published snapshot and a normal lock skip are successful outcomes. A failed result or thrown exception counts as an error.
The adapter returns the original result and rethrows the original exception. It does not retry a write.

Start with `REACTOR_GLOWROOT_PROFILE=micro`. No payload, key value, SQL value or customer data is captured.
For a normal Apache Dubbo `ServiceConfig` provider with its filter chain enabled, start the same
standalone lifecycle and add `java-rust-glowroot-spring-dubbo-adapter:0.7.1`. Its SPI observes completion.
The minimal provider sample deliberately bypasses SPI filters. Use its `full-provider,glowroot`
profiles and the `java-rust-dubbo:0.8.1` dispatcher hook instead. It decorates selected dispatchers at
startup and does not restore the full Apache filter chain. Spring is not required in either case.
Keep both libraries out of applications that do not need those adapters.
