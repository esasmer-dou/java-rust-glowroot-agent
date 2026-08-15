# Benchmark Support

This directory is test infrastructure. None of its gRPC, protobuf, Netty, or HdrHistogram Java
dependencies are packaged in `java-rust-glowroot-agent` or a user application.
The mock collector is never deployed to production and does not replace the existing Glowroot
Central/collector.

## Runner Topology

Fast local diagnosis and release evidence have different jobs:

| Stage | Runner | Purpose |
|---|---|---|
| Unit and decision statistics | GitHub-hosted Linux | Clean, reproducible correctness checks |
| Focused diagnosis | Local Docker/WSL | Short feedback while changing code or gate logic |
| Full Spring and Rust-Java REST matrix | Dedicated `reactor-performance-native-linux` pool | Stable CPU topology, persistent caches, exact-commit release evidence |
| Package and release | GitHub-hosted Linux | Provenance, hashes, Maven Packages, and GitHub Release |

The local WSL runner is repository-scoped and accepts only jobs carrying `self-hosted`,
`reactor-wsl-smoke`, `linux`, and `x64`. It provides fast toolchain, protocol, and focused A/B checks.
It is not release-grade performance infrastructure: Docker Desktop virtual CPUs do not prove stable
physical-core isolation. A production self-hosted runner must use the distinct
`reactor-performance-native-linux` label, native Linux, at least eight dedicated logical CPUs,
12 GiB available to Docker, PowerShell, Maven, Docker socket access, and stable CPU frequency.

Production jobs no longer fall back to `ubuntu-latest`. The release workflow checks the actual job
labels and uploaded runner preflight evidence before accepting a successful run. The preflight
rejects WSL, containerized runners, used swap, insufficient CPU/RAM/disk, non-OpenJ9 Java, and more
than one runner listener on the host. This keeps a fast runner from becoming an invalid benchmark.

Install one runner per physical host. A pool of two hosts lets Spring and Rust-Java REST execute in
parallel. A one-host pool remains valid but executes them serially. Never add two runner services to
the same host merely to reduce elapsed time; simultaneous matrices would contaminate p99 and RSS.
See [`scripts/performance-runner/README.md`](../scripts/performance-runner/README.md).

Run **Production Runner Preflight** after installation. It validates the native host, Semeru,
Maven, Docker, CPU topology, swap, and runner-service count in a short job before the full matrix is
allowed to consume runner time.

Use **Performance Runner Smoke** after changing the WSL runner installation and for fast feedback.
Use **Production Gate** only for a release candidate. The release workflow still requires its
successful native-Linux evidence for the exact tag commit. Do not relabel a WSL/Docker Desktop host
as `reactor-performance-native-linux`; its warmup trend and RSS evidence are not equivalent.

Choose `release` for the adaptive gate. It starts with three independent JVM pairs and stops only
when every cell satisfies a stricter early-pass envelope. A boundary result automatically continues
to at most six pairs. Choose `extended` to require all six pairs for a benchmark-engine
investigation. Creating a tag reuses successful exact-commit evidence and does not run the matrix
again.

The dedicated runner service must configure four topology-calibrated roles. Values below describe
the current eight-logical-CPU runner; a different host must use four distinct physical groups:

```properties
REACTOR_BENCHMARK_APPLICATION_CPU_SET=4,5
REACTOR_BENCHMARK_RUNNER_CPU_SET=0,1
REACTOR_BENCHMARK_COLLECTOR_CPU_SET=2
REACTOR_BENCHMARK_ORCHESTRATOR_CPU_SET=6,7
```

The runner service itself is pinned to the orchestrator group. Preflight fails closed when any role
is missing, overlaps another physical group, or does not reserve the required SMT siblings.

For development-only feedback, run a short c64 small/raw JSON matrix locally:

```powershell
./benchmark/local_docker_quick_gate.ps1 -ApplicationKind rust-java-rest
```

Use `spring-boot` or `all` when needed. The generated evidence explicitly contains
`release_evidence=false`; GitHub release validation cannot consume it.

`mock-collector` parses messages using Glowroot's current protobuf contract. It verifies init,
aggregate, gauge, and trace payloads, including transaction-count versus histogram-count equality.
The footprint gate runs the same application image with the agent disabled and enabled.

## Spring Boot Gate

The Spring gate builds one executable Spring Boot image and runs it with the starter present in both
variants. Baseline keeps telemetry disabled. Candidate enables the MVC interceptor and standalone
Rust exporter through system properties. This is the recommended strict-memory production path.
The same-image design prevents application dependencies or JVM flags from being mistaken for agent
overhead.

The separate non-web correctness gate builds a real `WebApplicationType.NONE` executable with only
`spring-boot-starter` and the Glowroot starter. It fails if the runtime dependency graph contains
Spring Web MVC, Tomcat, or the Servlet API; it also fails unless the native JVM probe starts and MVC
beans remain absent:

```powershell
./benchmark/non_web_gate.ps1
```

This is a functional and dependency-surface gate, not an HTTP performance benchmark. The randomized
Spring matrix still measures the MVC hot path, while the non-web gate protects worker startup and
classpath behavior.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -MinimumPairRepeats 3 `
  -ConcurrencyLevels "64,256" `
  -HeavyConcurrencyLevels "64,128" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "12s" `
  -Warmup "5s" `
  -PreWarmCycles 2 `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 6 `
  -MaxWarmupConfirmationRounds 10 `
  -MaxWarmupRobustTrendPercent 3 `
  -MaxWarmupBorderlineRobustTrendPercent 5 `
  -MaxWarmupBorderlineMedianShiftPercent 3 `
  -MaxWarmupMedianAbsoluteDeviationPercent 4 `
  -MaxNon2xxDeltaPercentagePoints 0 `
  -MaxSaturatedNon2xxDeltaPercentagePoints 0.02 `
  -MaxAbsoluteNon2xxPercent 0.05 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Pass `-UseJavaAgentBootstrap` only when measuring the optional early-start bootstrap. That mode
starts OpenJ9's instrumentation subsystem and is reported separately; it does not inherit the
starter-only resident-memory certification. CI still verifies bootstrap argument mapping and the
executable-JAR startup path.

Each process first receives two equal pre-warm cycles. It then receives six measured warmup rounds.
Every round visits all endpoint classes in round-robin order. If the final stability window fails,
the gate adds at most ten full-matrix confirmation rounds without relaxing any threshold. Baseline
and candidate therefore receive the same work at the same process age. One persistent `wrk`
container is reused for the entire gate instead of creating a container for every sample.
The normalized Theil-Sen trend over the final six rounds normally must not exceed `3%`. A trend in
the `3-5%` boundary band passes only when the previous/recent three-round medians differ by no more
than `3%`. Median absolute deviation must remain within `4%` in both cases. This rejects a sustained
OpenJ9/JIT ramp without treating a near-plateau outlier as a trend. Full min/max range remains
diagnostic. Baseline and candidate always do
the same warmup work. Cells and variant order are randomized. On Linux, `-AutoSelectCpuRoles` samples physical
CPU groups after the build, chooses the
quietest group, reserves all of that group's SMT siblings for the application, and pins the load
runner, collector, and benchmark orchestrator to three other physical groups. The orchestrator never
shares the two-thread `wrk` group. Baseline and candidate then occupy the same reserved group in
alternating order. A post-build host preflight rejects a noisy core or excessive steal time instead
of publishing misleading evidence. Manual single-logical-CPU runs still measure the otherwise-idle
SMT sibling. These are host-quality checks, not product thresholds; rerun failed evidence on a quiet
node rather than relaxing the limits.

RPS, p99, and startup use the median of baseline/candidate pair deltas. This preserves each same-core
comparison instead of subtracting unrelated group medians. Non-2xx responses use a zero-delta gate
for normal cells. Only the explicitly saturated embedded REST heavy JSON c256 cell receives a `0.02` percentage-
point non-inferiority margin. The paired median, request-weighted aggregate, and peak envelope must
all stay within the cell margin, while baseline and candidate aggregate/peak rates must remain at
or below the absolute `0.05%` ceiling.
The worst individual paired delta remains in the report as a noise/overload diagnostic, but cannot
replace these population-level guards. Per-cell process RSS and cgroup maxima
remain diagnostics because independent OpenJ9 JIT/GC residency is noisy. After all RPS and p99
measurements finish, the Spring benchmark invokes one benchmark-only explicit full GC for both
variants and then applies the same idle window. This does not affect any latency or throughput
sample; it removes unrelated GC-phase noise from the retained-memory comparison. Embedded
Rust-Java REST uses the same idle window without an explicit GC. Each variant then receives five
memory samples at equal process age. The paired median of this retained process RSS and cgroup
memory must stay within `+3 MiB`. Additional
threads still use the worst paired delta. The separate footprint gates retain the stricter agent-owned
and exact-source resident checks. Spring and embedded Rust-Java each allow exactly one bounded,
isolated exporter thread when telemetry is enabled.

## Rust-Java REST Gate

The published `0.3.0` REST gate uses the same paired same-core engine. It verifies the framework
version and native ABI before building the image. Glowroot ABI `3` deliberately isolates telemetry
from Hyper on one `256 KiB` exporter thread; the earlier ABI `1` implementation had no telemetry
thread because it shared the framework runtime.

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -ApplicationKind rust-java-rest `
  -RequiredRestVersion "4.5.0" `
  -RequiredRestNativeAbi 29 `
  -PairRepeats 6 `
  -MinimumPairRepeats 3 `
  -ConcurrencyLevels "64,256" `
  -HeavyConcurrencyLevels "64,128" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "12s" `
  -Warmup "5s" `
  -PreWarmCycles 2 `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 6 `
  -MaxWarmupConfirmationRounds 10 `
  -MaxWarmupRobustTrendPercent 3 `
  -MaxWarmupBorderlineRobustTrendPercent 5 `
  -MaxWarmupBorderlineMedianShiftPercent 3 `
  -MaxWarmupMedianAbsoluteDeviationPercent 4 `
  -MaxNon2xxDeltaPercentagePoints 0 `
  -MaxSaturatedNon2xxDeltaPercentagePoints 0.02 `
  -MaxAbsoluteNon2xxPercent 0.05 `
  -MemoryLimit "128m" `
  -AllowedThreadDelta 1 `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

After that matrix, reuse its images for the protocol, collector-down, and optional bootstrap gate:

```powershell
.\benchmark\glowroot_gate.ps1 `
  -ProtocolOnly `
  -SkipBuild `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

The collector-down check keeps business HTTP traffic live, then waits for one real transport attempt.
The production export interval contract is 60 seconds, so a disconnected state alone is not enough:
the gate requires the failed attempt to be visible within 75 seconds. The exporter may attempt sooner.
This wait is protocol evidence; it does not relax the RPS, p99, error-rate, or 3 MiB resident-memory
thresholds.

The older dual-resident crossover tool remains available for machines with enough isolated physical
cores. It is diagnostic and is not the stable hosted-runner release gate:

```powershell
.\benchmark\glowroot_gate.ps1 -PairRepeats 4
```

The script writes its report under `benchmark/results/`.

Glowroot ABI `3` deliberately uses one isolated `256 KiB` Rust thread in both Spring and Rust-Java
REST. When validating the coordinated release, use REST ABI `29` and
`-AllowedThreadDelta 1`. Do not compare this source against the historical no-thread gate without
changing that contract explicitly.

## Runtime Profile Release Probe

`profile-switch/ProfileSwitchProbe.java` repeatedly raises the configured `micro` baseline to `jvm`,
`sql`, `full`, or `diagnostic` and returns through `restoreConfiguredProfile()`. It fails if
active/retired profile bytes remain, release stays pending, the Rust-owned JVM probe remains
registered, or its JNI global-reference count does not return to zero. The optional
`GLOWROOT_PROBE_COLLECTOR`, `GLOWROOT_PROBE_HOLD_MS`, and
`GLOWROOT_PROBE_REQUEST_TIMEOUT_MS` variables exercise a real JVM-gauge export window.
`ProfileHotPathProbe.java` alternates `micro` and `full` while timing the real JNI/native HTTP
aggregate call.

Build the exact-source standalone Linux native library first. Keep it outside packaged resources;
dirty local binaries are test inputs, not release artifacts:

```bash
cd ../rust-spring/glowroot-agent-native
CARGO_TARGET_DIR=/tmp/reactor-glowroot-profile cargo build --release
```

Compile the probes against `spring-boot-starter/target/classes`, then run them in the same OpenJ9
container image and limits used by the service:

```bash
java -Xms16m -Xmx64m -Xss256k \
  -cp spring-boot-starter/target/classes:target/profile-switch-classes \
  ProfileSwitchProbe /native/librust_glowroot_agent.so full

java -Xms16m -Xmx64m -Xss256k \
  -cp spring-boot-starter/target/classes:target/profile-switch-classes \
  ProfileHotPathProbe /native/librust_glowroot_agent.so
```

Run each target profile in at least three fresh processes. Compare initial `micro`, first active, and
final `micro` RSS. A valid release has zero active/retired profile bytes after downgrade, no pending
release, no registered JVM probe, no cycle-by-cycle growth, and no repeatable HTTP hot-path
regression.

To run only current Glowroot wire compatibility and collector-down fail-open behavior after building
the benchmark images:

```powershell
.\benchmark\glowroot_gate.ps1 -ProtocolOnly -SkipBuild -FailOnGate
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

The original exact-source attribution evidence is tracked in
[`../docs/evidence/0.1.0-rc1/footprint-report.md`](../docs/evidence/0.1.0-rc1/footprint-report.md).
Stable release decisions use the exact-commit Spring and REST evidence attached to GitHub Release.
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
