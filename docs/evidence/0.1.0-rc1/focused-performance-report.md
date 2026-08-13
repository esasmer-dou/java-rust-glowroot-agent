# Glowroot Footprint And Performance Gate

The same application image was kept resident with telemetry disabled and enabled.
CPU slots were crossed in a second phase so a favourable CPU could not decide the result.

- Protocol gate: PASS
- Collector-down fail-open gate: PASS
- Optional Java agent bootstrap gate: PASS
- Resident crossover gate: PASS
- Startup gate: PASS

| Endpoint class | C | Pairs | Process RSS disabled/enabled MiB | RSS delta | Container delta | Useful 200 RPS delta | p99 delta | 503 delta | RPS/P99 within-phase variation | Gate |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| small-json-direct | 256 | 6 | 62,4/64,72 | 2,18 | 0,88 | -0,17% | 6,28% | 0 pp | 1,79/5,22% | PASS |

| Startup metric | Disabled median | Enabled median | Paired delta | Gate |
|---|---:|---:|---:|---|
| Internal ready | 1409 ms | 1373 ms | -5,74% | PASS |
| HTTP reachable | 2378 ms | 2216 ms | -8,48% | PASS |

Overall gate: **PASS**

A favourable median never overrides an unstable pair-delta gate.
This same-image gate measures active telemetry state. A release also needs a previous-version versus new-version artifact gate to include native code-page growth.
