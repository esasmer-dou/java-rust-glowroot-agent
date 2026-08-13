# Validation Evidence

[English](VALIDATION.md) | [Turkish](VALIDATION.tr.md)

## Release Decision

Version `0.2.0` has two intentionally different runtime shapes:

- Rust-Java REST reuses the framework's Rust runtime. The telemetry feature adds no OS thread.
- Spring Boot MVC loads the standalone Rust exporter. It adds one bounded thread with a `256 KiB`
  stack and one reused h2 collector connection.

The release workflow cannot publish a tag unless the exact tag commit has a successful Production
Gate run. The measured report and machine-readable summary from that run are copied into the GitHub
Release assets. A result from another branch or an older commit cannot satisfy this check.

## Verified Contracts

| Gate | Result | Contract |
| --- | --- | --- |
| Bootstrap JAR surface | PASS | One application class, no runtime dependency, no native binary, no transformer |
| Spring starter correctness | PASS | Route, mapped status, exact failure, bounded route cache, and async completion tests |
| Upstream Glowroot protocol | PASS | Init, aggregate, gauge, trace, and HdrHistogram payloads parsed by the pinned upstream schema |
| Collector unavailable | PASS | Business HTTP remains available; backlog and reconnect behavior stay bounded |
| Embedded agent-owned budget | PASS | `358,531` calculated bytes; hard state/reserve limit `384 KiB` |
| Embedded native attributed ceiling | PASS | `0.694 MiB`, including measured feature code pages; `0` additional threads |
| Embedded observed resident maximum | PASS | smaps RSS maximum `+1.817 MiB`, below the `+3 MiB` boundary |
| Clean standalone native provenance | PASS | Windows/Linux binaries built from clean revision `a1ed7f0dde4f7903b66589ed5d5a759d6b9c9802` |
| Spring performance matrix | RELEASE ENFORCED | Six paired runs, three endpoint classes, c64/c256, exact-commit evidence |
| Spring steady memory | RELEASE ENFORCED | Same full workload and process age; paired median RSS/cgroup delta must stay within `+3 MiB` |

The embedded footprint report remains under
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md). For stable
`0.2.0`, the authoritative Spring report is the `spring-boot-production-gate.md` asset attached to
the GitHub Release. Its JSON companion contains the exact thresholds and result.

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
non-2xx regression at zero percentage points, and additional threads at one or less. Build work is
finished before CPU selection. Linux then chooses the quietest physical CPU group, pins the load
runner and collector elsewhere. The paired SMT-sibling median must remain within `10%`, and every
steal-time window must remain within `1%`.

Per-cell RSS maxima remain diagnostics because OpenJ9 JIT/GC page residency can move in both
directions between independent processes. Memory is gated at a controlled point instead: each
variant completes the same warmup and full endpoint workload, waits for the same idle phase, and is
sampled five times at equal process age. The median paired process RSS and cgroup deltas must each
remain within `+3 MiB`. The source-attributed native ceiling remains a separate stricter gate.

## Reproduce The Gates

Spring production matrix:

```powershell
.\benchmark\spring_boot_gate.ps1 `
  -PairRepeats 6 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json,raw-json,heavy-json" `
  -Duration "15s" `
  -Warmup "8s" `
  -AutoSelectCpuRoles `
  -AllowRunnerCollectorSiblingSharing `
  -FailOnGate
```

Protocol and collector-down fail-open gate:

```powershell
.\benchmark\glowroot_gate.ps1 -ProtocolOnly -FailOnGate
```

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
