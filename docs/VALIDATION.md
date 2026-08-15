# Validation Evidence

[English](VALIDATION.md) | [Turkish](VALIDATION.tr.md)

## Release Decision

Version `0.3.0` has two intentionally different packaging shapes:

- Rust-Java REST uses the exporter included in the framework's native library.
- Spring Boot, with or without a web server, loads the standalone Rust exporter library. Servlet MVC
  adds only the optional HTTP interceptor.

Both paths isolate telemetry from application and Hyper workers on one bounded thread with a
`256 KiB` stack and one reused HTTP/2 collector connection.

The release workflow cannot publish a tag unless the exact tag commit has a successful Production
Gate run. The measured report and machine-readable summary from that run are copied into the GitHub
Release assets. A result from another branch or an older commit cannot satisfy this check.

This section records the published `0.3.0` contract. One isolated exporter thread serves both
Spring and Rust-Java REST with Glowroot ABI `3` / REST ABI `29`.

## Current Source Profile Gate

The runtime-profile lifecycle was tested with the exact-source Linux standalone `.so`, Semeru
OpenJ9 21, one CPU, a `256 MiB` container limit, `-Xms16m -Xmx64m -Xss256k`, and an unavailable
collector. Each profile completed `100` upgrade/downgrade cycles in three independent processes.

| Target profile | Median initial `micro` RSS | Median first active RSS delta | Median final `micro` RSS delta after 100 cycles |
| --- | ---: | ---: | ---: |
| `jvm` | `61,868 KiB` | `+4,380 KiB` | `+800 KiB` |
| `sql` | `61,724 KiB` | `+4,052 KiB` | `+464 KiB` |
| `full` | `61,900 KiB` | `+4,512 KiB` | `+812 KiB` |

Every final state reported `active_profile_memory_ceiling_bytes=0`,
`retired_profile_memory_ceiling_bytes=0`, `profile_release_pending=false`, and
`jvm_probe_registered=false` and `jvm_probe_owned_global_refs=0`. All `100` logical releases completed; no cycle-by-cycle growth was
observed. The current conservatively accounted `full` native state ceiling is approximately
`79.3 KiB`. Most of the
first-use RSS delta is OpenJ9 class/JIT/JMX/error warm state, not retained native profile queues.

One representative `full -> micro` run measured release latency at `181 us` p50, `294 us` p95, and
`350 us` p99. The HTTP native-record hot path remained around `30-32 ns` per call in alternating
`micro`/`full` rounds and showed no repeatable profile regression. OpenJ9 created one JVM-owned
`Finalizer thread` after management APIs were first used; the agent exporter is native and does not
appear in the Java thread list.

After adding export-generation, in-flight error-capture ownership, restart-safe release polling, and
the fixed non-route Java error identity, `full -> configured micro` was rerun from the latest
exact-source binary in three fresh `128 MiB`, one-CPU containers. Median initial RSS was
`61,608 KiB`; median first-`full` delta was `+4,412 KiB`; median final delta after `100` cycles was
`+828 KiB`. Median process-level downgrade/release percentiles were p50 `200 us`, p95 `269 us`, and
p99 `299 us`. One release maximum reached `41.7 ms` under scheduler/JIT noise and first-use JVM/JIT
upgrade maxima ranged from `50-100 ms`, but no pending release,
retained profile bytes, JNI probe, or cycle growth remained. Three order-balanced hot-path processes
measured `micro` at `27.22-28.78 ns` and `full` at `27.20-28.38 ns`; no repeatable profile-path
regression was established on this host.

After moving JVM discovery, polling, diagnostics, and diagnostic file I/O out of Java helpers, the
ABI `3` exact-source binary passed a focused OpenJ9 gate. An active `full` or `diagnostic` profile
owned `11` JNI global references in Rust; every return to `micro` reported `0`. A `100`-cycle `full`
run moved from `62,112 KiB` initial RSS to `62,808 KiB` final RSS. A diagnostic run completed a real
thread dump with `diagnostic_completed=1`, `diagnostic_failed=0`, and no retained profile bytes or
references. The coordinated REST ABI `29` probe also completed `100` cycles and returned all owned
state to zero.

A real mock-collector wire run received one init and one gauge message with `20` values. Ten values
were the Rust-collected heap, non-heap, memory-pool, and GC gauges. Three fresh hot-path processes
measured `micro` at `27.95-29.96 ns` and `full` at `28.45-31.99 ns` per native record call; their
within-process deltas were `1.79-6.88%`. These are focused ownership and protocol checks, not the
randomized full release matrix.

The coordinated REST ABI `29` Linux binary also completed `100` `full -> configured micro` cycles
with `active/retired=0`, no pending release, and no JNI JVM probe. Its final RSS was lower than its
initial RSS because glibc `malloc_trim(0)` can also return unrelated free process allocator pages.
That observation proves lifecycle completion, not isolated agent RSS savings; only a fresh-process
telemetry-off/on A/B gate can attribute resident memory to the feature.

Both standalone and coordinated REST probes also passed a hostile `Throwable` whose
`getStackTrace()` method throws. JNI cleared the pending probe exception, incremented the bounded
drop counter, and left the business/error flow unchanged.

These tests prove bounded ownership and repeatable release, not a new stable release. The exact
source still requires the randomized full HTTP RPS/p99/RSS matrix and clean Windows/Linux packaged
native artifacts before ABI `3` can be published.

## Verified Contracts

| Gate | Result | Contract |
| --- | --- | --- |
| Bootstrap JAR surface | PASS | One application class, no runtime dependency, no native binary, no transformer |
| Spring starter correctness | PASS | Web-independent lifecycle plus route, mapped status, exact failure, bounded route cache, and async completion tests |
| Spring non-web integration | PASS | Real `WebApplicationType.NONE` executable starts JVM telemetry with no MVC bean or web runtime dependency |
| Upstream Glowroot protocol | PASS | Init, aggregate, gauge, trace, and HdrHistogram payloads parsed by the pinned upstream schema |
| Collector unavailable | PASS | Business HTTP remains available; backlog and reconnect behavior stay bounded |
| Embedded agent-owned budget | PASS | `358,531` calculated bytes; hard state/reserve limit `384 KiB` |
| Embedded native attributed ceiling | PASS | `0.694 MiB`, including measured feature code pages; `0` additional threads |
| Embedded observed resident maximum | PASS | smaps RSS maximum `+1.817 MiB`, below the `+3 MiB` boundary |
| Clean standalone native provenance | PASS | Windows/Linux binaries built from clean revision `a1ed7f0dde4f7903b66589ed5d5a759d6b9c9802` |
| Rust-Java REST performance matrix | RELEASE ENFORCED | Six paired runs, three endpoint classes, c64/c256, REST `4.5.0`, native ABI `29` |
| Rust-Java REST protocol and fail-open | RELEASE ENFORCED | Upstream wire schema, one failed transport attempt within the 75-second observation window, continued business HTTP availability, and optional `-javaagent` bootstrap must all pass |
| Spring performance matrix | RELEASE ENFORCED | Six paired runs, three endpoint classes, c64/c256, exact-commit evidence |
| Spring retained memory | RELEASE ENFORCED | Same full workload and process age; after performance sampling, both variants receive one benchmark-only full GC and the same idle window; paired median RSS/cgroup delta must stay within `+3 MiB` |

The two production performance jobs run only on the
`self-hosted,linux,x64,reactor-performance-native-linux` runner class. Unit tests, package publishing,
and release orchestration remain on GitHub-hosted Linux. Release validation reads the GitHub job
labels and each job's preflight JSON, so hosted Linux, WSL, and containerized runners cannot be used
as performance evidence by accident. Use two separate runner hosts for parallel execution; use only
one runner service per host.

Local Docker uses [`local_docker_quick_gate.ps1`](../benchmark/local_docker_quick_gate.ps1) for fast
c64 small/raw JSON feedback. Its output is diagnostic and never satisfies the exact-commit release
gate.

The embedded footprint report remains under
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md). For stable
`0.3.0`, the authoritative reports are the `spring-boot-production-gate.md` and
`rust-java-rest-production-gate.md` assets attached to the GitHub Release. The release workflow
rejects a tag unless both reports came from one successful exact-commit Production Gate run.

## How The Spring Gate Works

The gate builds one Spring Boot image with the starter present. Baseline disables telemetry.
Candidate enables it. This keeps the application classes, dependencies, JVM flags, CPU quota, and
memory limit identical.

The matrix covers:

- small dynamic JSON;
- raw/precomputed JSON;
- dynamic heavy JSON;
- concurrency `64` and `256`;
- six paired baseline/candidate runs with alternating variant order.

Every performance cell must keep useful HTTP 200 RPS loss within `2%`, p99 regression within `10%`,
and additional threads at one or less. Normal cells use a zero-delta non-2xx gate. The deliberately
saturated embedded REST heavy JSON c256 cell uses a `0.02` percentage-point non-inferiority margin. Its paired
median, request-weighted aggregate, and peak envelope must stay within that margin. Baseline and
candidate aggregate and peak error rates must also stay at or below `0.05%`. A single-pair delta
remains visible as a diagnostic, but does not replace those population-level decisions. Build work is
finished before CPU selection. Linux then chooses the quietest physical CPU group, reserves all SMT
siblings in that group for the application, and pins the load runner and collector to another group.
Every steal-time window must remain within `1%`. A manually configured single-logical-CPU run also
keeps the paired SMT-sibling activity delta within `10%`.

Each application process receives exactly sixteen warmup rounds per endpoint. Every round visits all
configured endpoint classes in round-robin order. The production dual-slot gate also alternates
baseline and candidate requests within every endpoint round. Both JVMs receive the same work at the
same process age. This removes endpoint and variant-order bias and warms shared HTTP, servlet, and
JIT code. The
normalized Theil-Sen trend across the final six rounds normally may not exceed `3%`. A trend in the
`3-5%` boundary band passes only when previous/recent three-round medians differ by at most `3%`.
Median absolute deviation must remain within `4%` in both cases. This rejects a sustained OpenJ9
interpreter/JIT ramp without failing a release for a near-plateau outlier. Baseline and candidate
still perform the same fixed warmup work. The full range remains diagnostic, and every raw RPS
sample is attached to the release.

Per-cell RSS maxima remain diagnostics because OpenJ9 JIT/GC page residency can move in both
directions between independent processes. Memory is gated at a controlled point instead: each
variant completes the same warmup and full endpoint workload, waits for the same idle phase, and is
sampled five times at equal process age. The median paired process RSS and cgroup deltas must each
remain within `+3 MiB`. The source-attributed native ceiling remains a separate stricter gate.

## How The Rust-Java REST Gate Works

The REST gate checks out the published `rust-java-rest:4.5.0` source tag and rejects any native ABI
other than `29`. It builds one minimal production image and runs telemetry off/on sequentially on
the same physical CPU. The matrix uses the same small JSON, raw JSON, and heavy JSON classes and the
same c64/c256 limits as the Spring gate. Embedded telemetry may add only the single bounded exporter
thread required by the architecture.

A second gate validates messages against the pinned Glowroot wire schema. It also stops the
collector and proves that business HTTP remains available. Finally, it starts the same REST image
with the optional `-javaagent` bootstrap and verifies that the requested bounded native settings
were applied. All three checks are mandatory for a stable tag.

## Reproduce The Gates

Spring production matrix:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 16 `
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

Rust-Java REST production matrix:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -ApplicationKind rust-java-rest `
  -RequiredRestVersion "4.5.0" `
  -RequiredRestNativeAbi 29 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -MinWarmupRounds 3 `
  -MaxWarmupRounds 16 `
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

Protocol and collector-down fail-open gate:

```powershell
.\benchmark\glowroot_gate.ps1 `
  -ProtocolOnly `
  -SkipBuild `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

The fail-open phase first proves that business HTTP remains successful while the collector is down.
It then waits for the first real transport attempt under the 60-second export interval contract and
requires `export_failure >= 1` within 75 seconds. The attempt may happen sooner. Merely seeing
`connected=false` before an export attempt does not pass the release gate.

Embedded exact-source footprint gate:

```powershell
.\benchmark\feature_artifact_footprint.ps1 `
  -RepeatCount 3 `
  -Concurrency 256 `
  -RequestsPerEndpoint 4096 `
  -FailOnGate
```

Do not use `-SkipHostPreflight` for release evidence. A host-quality rejection is not a product
regression and must be rerun on a quiet node.

## Build Evidence

- Full native runtime: `57` tests and Clippy with warnings denied.
- Standalone Glowroot runtime: `28` tests and Clippy with warnings denied.
- Java reactor: `15` tests, packaged-native verification, and OpenJ9 JNI integration.
- Executable Spring Boot smoke: starter plus optional one-class `-javaagent` bootstrap.
- Executable non-web Spring Boot smoke: `WebApplicationType.NONE`, active native JVM probe, no MVC
  beans, and no Spring Web/Tomcat/Servlet runtime dependency.
- Native build matrix: Windows x64 and Linux glibc x64 for full and standalone runtimes.
- Native toolchain: Rust `1.91.0`; Java toolchain: Semeru OpenJ9 `21`.
- Glowroot wire reference: upstream revision `622dc6f800228cccc6fa37b0ed9e779446d7c41e`.

## Deployment Validation

The release gates prove the bounded product contract on the published CI image. Before a production
rollout, repeat a short smoke and representative load on your own Kubernetes node class, collector
version, CPU limit, memory limit, network policy, and endpoint mix. This environment check does not
change the released ABI; it verifies that your deployment assumptions match the tested profile.

The hard-memory profile resolves collector DNS at startup and retains at most four addresses. Use a
stable Kubernetes `ClusterIP` Service or localhost sidecar. Restart the pod when a headless or
dynamically changing collector target is required. Use a service mesh or sidecar for TLS/mTLS.
