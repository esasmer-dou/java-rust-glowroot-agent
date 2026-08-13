# Benchmark Support

This directory is test infrastructure. None of its gRPC, protobuf, Netty, or HdrHistogram Java
dependencies are packaged in `java-rust-glowroot-agent` or a user application.
The mock collector is never deployed to production and does not replace the existing Glowroot
Central/collector.

`mock-collector` parses messages using Glowroot's current protobuf contract. It verifies init,
aggregate, gauge, and trace payloads, including transaction-count versus histogram-count equality.
The footprint gate runs the same application image with the agent disabled and enabled.

## Spring Boot MVC Gate

The Spring gate builds one executable Spring Boot image and runs it with the starter present in both
variants. Baseline keeps telemetry disabled. Candidate enables the MVC interceptor and standalone
Rust exporter through system properties. This is the recommended strict-memory production path.
The same-image design prevents application dependencies or JVM flags from being mistaken for agent
overhead.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 4 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -FailOnGate
```

Pass `-UseJavaAgentBootstrap` only when measuring the optional early-start bootstrap. That mode
starts OpenJ9's instrumentation subsystem and is reported separately; it does not inherit the
starter-only resident-memory certification. CI still verifies bootstrap argument mapping and the
executable-JAR startup path.

Each configured endpoint receives its own warmup. Cells and variant order are randomized. On a
small Linux runner, use `-SequentialVariants` so baseline and candidate occupy the same isolated
application CPU in alternating order. The load runner and mock collector may share a separate CPU
or SMT group, but neither may share the application core.

RPS, p99, process RSS, cgroup memory, and startup use the median of baseline/candidate pair deltas.
This preserves each same-core comparison instead of subtracting unrelated group medians. Process
RSS and cgroup maxima remain visible diagnostics. Exact errors and additional threads use the worst
paired delta, so a failed response or unexpected thread cannot be hidden by a median. The separate
footprint gates retain the strict agent-owned and exact-source resident maximum checks. The Spring
path allows at most one additional thread; the embedded Rust-Java path allows none.

Run the full gate from the project root:

```powershell
.\benchmark\glowroot_gate.ps1 -PairRepeats 4
```

The script writes its report under `benchmark/results/`.

To verify only current Glowroot wire compatibility and collector-down fail-open behavior:

```powershell
.\benchmark\glowroot_gate.ps1 -ProtocolOnly -FailOnGate
```

This short mode makes no RSS, RPS, p99, or startup claim.

To separate native telemetry state from the OpenJ9 `-javaagent` bootstrap surface, run:

```powershell
.\benchmark\footprint_attribution.ps1 -RepeatCount 6 -FailOnGate
```

This runs disabled, native-properties, and `-javaagent` variants with the same image and workload.
Repeat count must be a multiple of three so every variant occupies every CPU slot equally. The
report includes `smaps`, cgroup anon/file memory, mapped
instrumentation pages, and thread count. It is an attribution tool, not a replacement for the full
RPS/p99 gate.

To include the native code pages of the feature itself without mixing unrelated framework-version
changes into the result, run the same-source feature gate:

```powershell
.\benchmark\feature_artifact_footprint.ps1 `
  -RepeatCount 3 `
  -RequestsPerEndpoint 4096 `
  -FailOnGate
```

The script compiles one Linux SO without `glowroot` and uses the coordinated current SO with
`glowroot`. Both images compile the same current minimal application source. Every variant executes
the same fixed request count. A SHA-256 fingerprint covers `Cargo.toml`, `Cargo.lock`, build files,
the toolchain file, and every Rust source file. `-SkipNativeBuild` rejects a missing or stale
fingerprint instead of silently comparing against an old feature-disabled binary.

The artifact gate requires every observed `VmRSS`, smaps RSS, and cgroup-current delta to remain at
or below `+3 MiB`, with no additional thread. Medians cannot override a failing maximum. The Rust
startup budget separately enforces the stricter `1 MiB` agent-attributed boundary.
`gate-summary.json` is machine-readable.

The current exact-source release evidence is tracked in
[`../docs/evidence/0.1.0-rc1/footprint-report.md`](../docs/evidence/0.1.0-rc1/footprint-report.md).
The deterministic embedded-native ceiling is
`0.694 MiB` with `0` extra threads. The independent-process resident gate passed with maxima of
`VmRSS +1.742 MiB`, smaps RSS `+1.817 MiB`, and cgroup current `+1.754 MiB`. The optional
`-javaagent` path is reported separately and is not certified for the strict resident path.

Release-grade performance scripts run a Windows host preflight before starting load. By default,
average host CPU must be at most `15%`, peak CPU at most `40%`, and free virtual memory at least
`3072 MiB`. The check runs before expensive setup and again after a build, before result directories
or load containers are created. `HostStabilizationSeconds` defaults to `15`, so short Docker
build/isolation spikes expire before the second sample. A failed preflight prevents noisy results
from being mislabeled as regressions or passes. Do not use `-SkipHostPreflight` for release evidence.

Before release, include native code-page growth by comparing the last published framework artifact
with the new framework plus enabled agent:

```powershell
.\benchmark\artifact_upgrade_gate.ps1 -BaselineTag v4.3.0 -PairRepeats 3
```

Both images compile the same current minimal application source. The baseline gets its core/runtime
and codegen JARs from a temporary detached worktree at the tag. The candidate gets the current
artifacts and enables the micro agent. The worktree is removed after the run; the active framework
checkout is not modified.
