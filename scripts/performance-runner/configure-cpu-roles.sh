#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="/opt/actions-runner/reactor-performance"
APPLICATION_CPUS=""
RUNNER_CPUS=""
COLLECTOR_CPU=""

usage() {
  cat <<'EOF'
Usage:
  sudo ./configure-cpu-roles.sh \
    --application-cpus CPU_SET \
    --runner-cpus CPU_SET \
    --collector-cpu CPU \
    [--install-root /opt/actions-runner/reactor-performance]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --application-cpus) APPLICATION_CPUS="$2"; shift 2 ;;
    --runner-cpus|--runner-cpu) RUNNER_CPUS="$2"; shift 2 ;;
    --collector-cpu) COLLECTOR_CPU="$2"; shift 2 ;;
    --install-root) INSTALL_ROOT="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Run this command with sudo." >&2
  exit 1
fi
if [[ ! "$APPLICATION_CPUS" =~ ^[0-9]+([,-][0-9]+)*$ ]]; then
  echo "Invalid application CPU set: $APPLICATION_CPUS" >&2
  exit 2
fi
if [[ ! "$RUNNER_CPUS" =~ ^[0-9]+([,-][0-9]+)*$ || ! "$COLLECTOR_CPU" =~ ^[0-9]+$ ]]; then
  echo "Runner must contain a CPU set and collector must contain one logical CPU." >&2
  exit 2
fi
if [[ ! -x "$INSTALL_ROOT/svc.sh" || ! -f "$INSTALL_ROOT/.runner" ]]; then
  echo "GitHub Actions runner installation is not valid: $INSTALL_ROOT" >&2
  exit 1
fi
role_cpus="${APPLICATION_CPUS//,/ } ${RUNNER_CPUS//,/ } ${COLLECTOR_CPU}"
expanded_role_cpus=""
for token in $role_cpus; do
  if [[ "$token" =~ ^([0-9]+)-([0-9]+)$ ]]; then
    expanded_role_cpus+=" $(seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}")"
  else
    expanded_role_cpus+=" $token"
  fi
done
for cpu in $expanded_role_cpus; do
  if [[ ! -d "/sys/devices/system/cpu/cpu${cpu}" ]]; then
    echo "Logical CPU is not available: $cpu" >&2
    exit 1
  fi
done

runner_user="$(stat -c '%U' "$INSTALL_ROOT/.runner")"
runner_group="$(stat -c '%G' "$INSTALL_ROOT/.runner")"
runner_env="$INSTALL_ROOT/.env"
filtered_env="$(mktemp)"
trap 'rm -f "$filtered_env"' EXIT
if [[ -f "$runner_env" ]]; then
  grep -Ev '^REACTOR_BENCHMARK_(APPLICATION_CPU_SET|RUNNER_CPU_SET|COLLECTOR_CPU_SET)=' \
    "$runner_env" >"$filtered_env" || true
fi
cat >>"$filtered_env" <<EOF
REACTOR_BENCHMARK_APPLICATION_CPU_SET=$APPLICATION_CPUS
REACTOR_BENCHMARK_RUNNER_CPU_SET=$RUNNER_CPUS
REACTOR_BENCHMARK_COLLECTOR_CPU_SET=$COLLECTOR_CPU
EOF

(
  cd "$INSTALL_ROOT"
  ./svc.sh stop
)
install -o "$runner_user" -g "$runner_group" -m 0644 "$filtered_env" "$runner_env"
(
  cd "$INSTALL_ROOT"
  ./svc.sh start
)

echo "Runner CPU roles updated: application=$APPLICATION_CPUS runner=$RUNNER_CPUS collector=$COLLECTOR_CPU"
