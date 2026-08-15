# Dedicated Performance Runner

[English](README.md) | [Turkish](README.tr.md)

The production matrix runs on a dedicated Linux host. Local Docker and WSL remain fast development
tools, but their results cannot approve a release.

## Required Host

Use one runner service per host. The host needs:

| Resource | Minimum |
| --- | --- |
| Operating system | Native Ubuntu, Debian, RHEL/CentOS, Rocky, or AlmaLinux x64; WSL and containers are rejected |
| CPU | 8 logical CPUs and at least 4 physical CPU groups |
| Memory | 12 GiB available to the host and Docker |
| Disk | 20 GiB free on `/` |
| Runtime | Docker Engine, PowerShell, Maven, Git |
| Java | Semeru OpenJ9 21, installed per job by `actions/setup-java` |

Use two separate physical hosts to run the Spring and Rust-Java REST jobs in parallel. Do not install
two runner services on one host. They would compete for CPU cache, memory bandwidth, and Docker and
would invalidate latency and RSS evidence.

## Install

Generate a short-lived registration token from an administrator workstation. Copy only the printed
token to the Linux host through your approved secure channel:

```bash
gh api -X POST \
  repos/esasmer-dou/java-rust-glowroot-agent/actions/runners/registration-token \
  --jq .token
```

On the dedicated Linux host, clone the repository. Read the token without writing it to shell
history, then run the installer:

```bash
read -rsp "Runner registration token: " RUNNER_TOKEN && echo
sudo ./scripts/performance-runner/install-native-linux.sh \
  --repository https://github.com/esasmer-dou/java-rust-glowroot-agent \
  --token "$RUNNER_TOKEN" \
  --name perf-linux-01
unset RUNNER_TOKEN
```

The installer verifies the GitHub runner download digest, installs Maven `3.9.9`, and supports both
Debian and RHEL package families. It installs one system service with the
`reactor-performance-native-linux` label. Docker must already be installed and healthy.

## Verify

Run the preflight directly:

```bash
pwsh ./benchmark/performance_runner_preflight.ps1 \
  -RunnerClass reactor-performance-native-linux \
  -EvidencePath /tmp/reactor-runner-preflight.json
```

Then start **Production Gate** from GitHub Actions. GitHub keeps orchestration, exact-commit checks,
artifacts, package publication, and release approval. Only the Spring and REST performance jobs run
on the self-hosted pool.

Run **Production Runner Preflight** once before the full gate. It fails in minutes when host sizing,
CPU policy, swap, Docker, Java, or runner registration is wrong.

The release workflow checks both the job labels and the uploaded preflight JSON. A hosted runner,
WSL host, containerized runner, undersized host, active swap, or second runner listener cannot
produce accepted release evidence.

## Fast Local Feedback

Use local Docker before pushing:

```powershell
./benchmark/local_docker_quick_gate.ps1 -ApplicationKind rust-java-rest
```

Use `-ApplicationKind spring-boot` for Spring or `-ApplicationKind all` for both. This quick gate
uses c64, small/raw JSON, and three short pairs. It catches large regressions quickly, but it is
marked `development-only` and cannot replace the full release matrix.
