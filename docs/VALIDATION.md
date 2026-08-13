# Validation Evidence

[English](VALIDATION.md) | [Turkish](VALIDATION.tr.md)

## Current Decision

The embedded Rust-Java telemetry path passes correctness, Glowroot protocol, collector-down
fail-open, the source-enforced `1 MiB` agent-owned budget, and the independent-process `3 MiB`
resident-memory boundary. It adds no OS thread.

These are two different contracts:

- `1 MiB` is the deterministic ceiling for state and code pages attributable to the agent.
- `3 MiB` is the conservative maximum allowed for paired process `VmRSS`, smaps RSS, and cgroup
  current. It includes OpenJ9, allocator, and page-residency noise between independent processes.

The optional `-javaagent` convenience JAR is not part of the strict resident-memory certification.
Use native properties or environment variables for the hard-budget production path.

| Gate | Result | Current evidence |
| --- | --- | --- |
| Runtime JAR surface | PASS | `5.71 KiB`, one bootstrap class, no runtime dependencies |
| Upstream protocol | PASS | Generated upstream parser accepted init, aggregate, gauge, trace, and HdrHistogram payloads |
| Optional Java bootstrap | PASS | Agent arguments were translated without installing a transformer |
| Collector unavailable | PASS | HTTP remained available and no unbounded telemetry backlog formed |
| Source memory budget | PASS | `358,531` calculated bytes; hard state/reserve limit is `384 KiB` |
| Embedded-native attributed ceiling | PASS | `0.694 MiB`, including measured native feature pages |
| Additional agent threads | PASS | `0`; the framework Tokio runtime is reused |
| Maximum process `VmRSS` delta | PASS | `+1.742 MiB`; product boundary is `+3.000 MiB` |
| Maximum smaps RSS delta | PASS | `+1.817 MiB`; product boundary is `+3.000 MiB` |
| Maximum cgroup-current delta | PASS | `+1.754 MiB`; product boundary is `+3.000 MiB` |
| Focused c256 small-direct performance | PASS | RPS `-0.17%`, p99 `+6.28%`, `503` delta `0` |
| Full c64/c256 endpoint matrix | OPEN | Latest workstation attempt was rejected by the host-noise preflight |

The footprint evidence is tracked in
[`evidence/0.1.0-rc1/footprint-report.md`](evidence/0.1.0-rc1/footprint-report.md). The focused
performance evidence is in
[`evidence/0.1.0-rc1/focused-performance-report.md`](evidence/0.1.0-rc1/focused-performance-report.md).
The refreshed protocol and fail-open evidence is in
[`evidence/0.1.0-rc1/protocol-report.md`](evidence/0.1.0-rc1/protocol-report.md).

## How To Read The Footprint Result

The exact-source footprint gate uses three balanced CPU-slot phases. Every variant runs the same
work sequentially on the same physical-core slot and is measured at the same process age. The script
fingerprints the complete native source input before using a feature-disabled SO. `-SkipNativeBuild`
rejects a missing or stale fingerprint, so an old baseline binary cannot silently produce a pass.

Embedded-native median deltas were:

| Measure | Median | Maximum |
| --- | ---: | ---: |
| Process `VmRSS` | `+1.676 MiB` | `+1.742 MiB` |
| smaps RSS | `+1.711 MiB` | `+1.817 MiB` |
| cgroup current | `+0.461 MiB` | `+1.754 MiB` |
| cgroup socket | `0 MiB` | `0 MiB` |
| Threads | `0` | `0` |

The optional convenience JAR had a source-attributed ceiling of `0.741 MiB`, but its observed
process/smaps maximum was about `3.055 MiB`. OpenJ9 instrumentation bootstrap is measurable even
without a transformer. The JAR remains useful for argument translation, but it is reported
separately and does not inherit the embedded-native certification.

## Performance Decision

The current micro default is `reactor.glowroot.http.sample-rate=256`. HTTP `5xx` remains exact;
successful requests are represented by weighted samples. In the focused c256 small-direct gate:

- useful HTTP 200 RPS changed by `-0.17%`;
- p99 changed by `+6.28%`;
- `503` changed by `0` percentage points;
- process RSS changed by `+2.18 MiB` and container memory by `+0.88 MiB`.

This cell passed the `-2%` RPS, `+10%` p99, `+2` percentage-point `503`, and `+3 MiB` memory gates.
It does not prove every endpoint class. A later c64 run had large baseline variation and is
`INCONCLUSIVE`, not a regression and not a pass.

The benchmark now fails before load when a Windows host is too noisy. Defaults are average CPU at
most `15%`, peak CPU at most `40%`, and free virtual memory at least `3072 MiB`. Do not use
`-SkipHostPreflight` for release evidence.

## Reproduce The Gates

Footprint and source-provenance gate:

```powershell
.\benchmark\feature_artifact_footprint.ps1 `
  -RepeatCount 3 `
  -Concurrency 256 `
  -RequestsPerEndpoint 4096 `
  -FailOnGate
```

Protocol, optional bootstrap, and collector-down fail-open gate:

```powershell
.\benchmark\glowroot_gate.ps1 -ProtocolOnly -FailOnGate
```

Full endpoint matrix on a quiet target node:

```powershell
.\benchmark\glowroot_gate.ps1 `
  -PairRepeats 4 `
  -ConcurrencyLevels "64,256" `
  -EndpointClasses "small-json-direct,direct-json-writer,raw-json" `
  -HttpSampleRate 256 `
  -Duration "20s" `
  -Warmup "8s" `
  -FailOnGate
```

Every required endpoint/concurrency cell must pass. `INCONCLUSIVE` is not a pass.

## Verified Build Gates

- `57` Rust tests with all features passed.
- Rust Clippy with all features and all targets passed.
- `30` Rust tests and Clippy passed with `glowroot` excluded from the native feature set.
- Windows OpenJ9 JNI smoke passed.
- `rust-java-rest` Maven `clean verify` passed with ABI and native provenance checks.
- `java-rust-cache` Maven `clean verify` passed with the coordinated native artifacts.
- `java-rust-glowroot-agent` Maven `clean verify` passed.
- Agent runtime dependency tree is empty.
- Windows DLL and Linux SO were built from the coordinated native source revision.
- Upstream Glowroot checkout stayed read-only at
  `622dc6f800228cccc6fa37b0ed9e779446d7c41e`.

## Remaining Release Evidence

- Run the full c64/c256 endpoint matrix on the target Kubernetes node class and OpenJ9 image.
- Run the previous-release versus candidate artifact-upgrade gate to include native code-page growth.
- Publish coordinated Rust-Java REST ABI `28` binaries; do not mix them with ABI `26`.
- Re-run protocol compatibility against the exact production Glowroot Central version.
- Validate plaintext h2 network policy or terminate TLS/mTLS in a localhost sidecar or service mesh.
- Keep Spring Boot support unclaimed until a separate adapter and Spring image pass the same gates.

The hard-memory profile resolves collector DNS once at startup and retains at most four unique IP
addresses. Use a stable Kubernetes `ClusterIP` Service or localhost sidecar. Headless or dynamically
changing collector DNS is outside this profile and requires a pod restart.
