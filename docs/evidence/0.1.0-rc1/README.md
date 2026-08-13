# 0.1.0-rc1 Validation Evidence

These files are immutable summaries copied from the generated benchmark workspaces used for the
first release candidate. They keep the release claims reviewable without committing raw process
maps, application logs, or temporary container output.

| Evidence | Result | Scope |
| --- | --- | --- |
| [Embedded footprint](footprint-report.md) | PASS | Same-source feature-off versus embedded native telemetry, three balanced phases |
| [Focused performance](focused-performance-report.md) | PASS | c256 small-direct, six crossed pairs, startup and protocol checks |
| [Protocol and fail-open](protocol-report.md) | PASS | Init, aggregate, HdrHistogram, gauges, traces, optional bootstrap, collector unavailable |

Machine-readable summaries are stored beside each report:

- [footprint-gate-summary.json](footprint-gate-summary.json)
- [focused-performance-gate-summary.json](focused-performance-gate-summary.json)
- [protocol-gate-summary.json](protocol-gate-summary.json)

The full c64/c256 endpoint matrix is not represented as a pass. The workstation host-noise
preflight rejected that run. This is why `0.1.0-rc1` remains a pre-release.
