# Glowroot Footprint Attribution

All variants use the same workload and JVM profile. Each phase runs all variants sequentially on the same physical-core CPU slot.
Variant order and the physical-core slot rotate across phases, removing simultaneous-JVM and fixed-slot bias.
Every process is measured at age >= 75 seconds; the maximum allowed within-phase age spread is 5 seconds.
Application slots are 0,2,4. The sequential load runner (7) and collector (6) use separate SMT siblings on the fourth physical core.
The resident gate uses the collector's fixed container IP; the protocol gate separately validates Kubernetes-style DNS.
Disabled image: java-rust-glowroot-agent:feature-off. Native and javaagent images: java-rust-glowroot-agent:feature-on / java-rust-glowroot-agent:feature-on.

| Variant | VmRSS MiB | smaps RSS | PSS | Private dirty | Anonymous | cgroup current | cgroup anon | cgroup sock | Threads | Instrumentation RSS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| disabled | 54.582 | 54.648 | 53.512 | 28.809 | 28.809 | 34.754 | 29.02 | 0 | 22 | 0 |
| native-properties | 55.383 | 55.52 | 54.43 | 29.125 | 29.125 | 35.215 | 29.34 | 0 | 22 | 0 |
| javaagent | 56.695 | 56.758 | 55.617 | 30.348 | 30.348 | 36.438 | 30.559 | 0 | 22 | 0.047 |

| Attribution | VmRSS delta | smaps RSS delta | PSS delta | Private dirty delta | Anonymous delta | cgroup current delta | cgroup sock delta | Thread delta |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Native telemetry state | 1.676 | 1.711 | 1.647 | 0.316 | 0.316 | 0.461 | 0 | 0 |
| Java agent bootstrap over native mode | 1.312 | 1.238 | 1.187 | 1.324 | 1.324 | 1.223 | 0 | 0 |
| Full javaagent versus disabled | 1.445 | 1.524 | 1.548 | 1.399 | 1.399 | 1.621 | 0 | 0 |

## Gate: PASS

The embedded-native source-enforced attributed ceiling is 0.694 MiB: 358531 bytes of bounded state/reserves/export work plus 0.352 MiB of native feature pages. The hard agent-attributed budget is 1 MiB.
The optional -javaagent convenience path is reported separately at 0.741 MiB after adding 0.047 MiB of Java instrumentation pages. It is not the hard-budget production mode.

The artifact attribution gate requires every observed native-properties versus feature-disabled paired delta to stay at or below +3 MiB for VmRSS, smaps RSS, and cgroup current. The maximum cgroup socket delta must stay within the 0.125 MiB kernel reserve, with at most 0 additional threads.
Private dirty, anonymous memory, and PSS remain supporting attribution evidence; they do not replace the total resident-memory gate.
Observed maximum within-phase process-age spread: 0.242 seconds.

| Observed paired delta | Minimum MiB | Median MiB | Maximum MiB | Spread MiB |
|---|---:|---:|---:|---:|
| VmRSS | -0.933 | 1.676 | 1.742 | 2.675 |
| smaps RSS | -0.906 | 1.711 | 1.817 | 2.723 |
| cgroup current | 0.16 | 0.461 | 1.754 | 1.594 |
| cgroup sock | 0 | 0 | 0 | 0 |

Optional -javaagent observed maxima: VmRSS 3.054 MiB, smaps RSS 3.055 MiB, cgroup current 1.684 MiB. These values are evidence only and do not inherit the embedded-native certification.

Attribution summaries are derived from phase-matched independent JVM processes. Repeat count is a multiple of three, so each variant occupies each physical-core slot equally.
OpenJ9 JIT, GC, allocator, and page residency differ between processes, but the strict release gate intentionally rejects even a noisy observed maximum above the product boundary. The Rust startup budget remains the agent-owned allocation contract; this test is the conservative artifact and resident-memory evidence gate.
