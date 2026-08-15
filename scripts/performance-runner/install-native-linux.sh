#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_URL=""
REGISTRATION_TOKEN=""
RUNNER_NAME="$(hostname)-reactor-performance"
RUNNER_USER="github-runner"
INSTALL_ROOT="/opt/actions-runner/reactor-performance"
LABELS="reactor-performance-native-linux,openj9,docker"
APPLICATION_CPUS=""
RUNNER_CPU=""
COLLECTOR_CPU=""

usage() {
  cat <<'EOF'
Usage:
  sudo ./install-native-linux.sh \
    --repository https://github.com/OWNER/REPOSITORY \
    --token SHORT_LIVED_REGISTRATION_TOKEN \
    [--name RUNNER_NAME] \
    [--application-cpus CPU_SET --runner-cpu CPU --collector-cpu CPU]

Run exactly one GitHub Actions runner service on each performance host. Use two physical hosts if
Spring and Rust-Java REST matrices must execute in parallel.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repository) REPOSITORY_URL="$2"; shift 2 ;;
    --token) REGISTRATION_TOKEN="$2"; shift 2 ;;
    --name) RUNNER_NAME="$2"; shift 2 ;;
    --application-cpus) APPLICATION_CPUS="$2"; shift 2 ;;
    --runner-cpu) RUNNER_CPU="$2"; shift 2 ;;
    --collector-cpu) COLLECTOR_CPU="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Run this installer with sudo." >&2
  exit 1
fi
if [[ -z "$REPOSITORY_URL" || -z "$REGISTRATION_TOKEN" ]]; then
  usage
  exit 2
fi
cpu_role_count=0
[[ -n "$APPLICATION_CPUS" ]] && cpu_role_count=$((cpu_role_count + 1))
[[ -n "$RUNNER_CPU" ]] && cpu_role_count=$((cpu_role_count + 1))
[[ -n "$COLLECTOR_CPU" ]] && cpu_role_count=$((cpu_role_count + 1))
if [[ $cpu_role_count -ne 0 && $cpu_role_count -ne 3 ]]; then
  echo "Configure application, runner, and collector CPU roles together, or omit all three." >&2
  exit 2
fi
if [[ $cpu_role_count -eq 3 ]]; then
  if [[ ! "$APPLICATION_CPUS" =~ ^[0-9]+([,-][0-9]+)*$ ]]; then
    echo "Invalid application CPU set: $APPLICATION_CPUS" >&2
    exit 2
  fi
  if [[ ! "$RUNNER_CPU" =~ ^[0-9]+$ || ! "$COLLECTOR_CPU" =~ ^[0-9]+$ ]]; then
    echo "Runner and collector roles must each contain one logical CPU." >&2
    exit 2
  fi
fi
if grep -Eqi 'microsoft|wsl' /proc/version; then
  echo "WSL cannot be registered as reactor-performance-native-linux." >&2
  exit 1
fi
if command -v systemd-detect-virt >/dev/null && systemd-detect-virt --container >/dev/null 2>&1; then
  echo "A container cannot host the production performance runner." >&2
  exit 1
fi
if pgrep -f 'Runner.Listener run' >/dev/null 2>&1; then
  echo "Another GitHub Actions runner is already active on this host." >&2
  exit 1
fi
if [[ -e "$INSTALL_ROOT/.runner" || -e "$INSTALL_ROOT/config.sh" ]]; then
  echo "Runner installation directory is not empty: $INSTALL_ROOT" >&2
  exit 1
fi
if ! command -v docker >/dev/null || ! docker info >/dev/null 2>&1; then
  echo "Install Docker Engine and verify it before installing the runner." >&2
  exit 1
fi

source /etc/os-release
case "${ID:-}" in
  ubuntu|debian)
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl git jq openssl tar
    package_family="debian"
    ;;
  rhel|centos|rocky|almalinux)
    dnf install -y ca-certificates curl git jq openssl tar gzip
    package_family="rhel"
    ;;
  *)
    echo "Supported systems are Ubuntu, Debian, RHEL, CentOS, Rocky Linux, and AlmaLinux." >&2
    exit 1
    ;;
esac

if ! command -v pwsh >/dev/null; then
  if [[ "$package_family" == "debian" ]]; then
    if [[ "$ID" == "ubuntu" ]]; then
      curl -fsSL -o /tmp/packages-microsoft-prod.deb \
        "https://packages.microsoft.com/config/ubuntu/${VERSION_ID}/packages-microsoft-prod.deb"
    else
      curl -fsSL -o /tmp/packages-microsoft-prod.deb \
        "https://packages.microsoft.com/config/debian/${VERSION_ID}/packages-microsoft-prod.deb"
    fi
    dpkg -i /tmp/packages-microsoft-prod.deb
    rm -f /tmp/packages-microsoft-prod.deb
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y powershell
  else
    rhel_major="${VERSION_ID%%.*}"
    curl -fsSL -o /etc/yum.repos.d/microsoft-prod.repo \
      "https://packages.microsoft.com/config/rhel/${rhel_major}/prod.repo"
    dnf install -y powershell
  fi
fi

MAVEN_VERSION="3.9.9"
if ! command -v mvn >/dev/null || ! mvn -version 2>&1 | grep -q "Apache Maven ${MAVEN_VERSION}"; then
  maven_archive="/tmp/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  maven_base="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries"
  curl -fsSL -o "$maven_archive" "${maven_base}/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
  maven_sha512="$(curl -fsSL "${maven_base}/apache-maven-${MAVEN_VERSION}-bin.tar.gz.sha512" | awk '{print $1}' | tr -d '[:space:]')"
  if [[ ! "$maven_sha512" =~ ^[0-9a-fA-F]{128}$ ]]; then
    echo "Cannot resolve the Maven SHA-512 checksum." >&2
    exit 1
  fi
  echo "${maven_sha512}  ${maven_archive}" | sha512sum --check --status
  rm -rf "/opt/apache-maven-${MAVEN_VERSION}"
  tar -xzf "$maven_archive" -C /opt
  rm -f "$maven_archive"
  ln -sfn "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn
fi

if ! id "$RUNNER_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$RUNNER_USER"
fi
usermod -aG docker "$RUNNER_USER"
install -d -o "$RUNNER_USER" -g "$RUNNER_USER" "$INSTALL_ROOT"

api_header=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  api_header=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi
release_json="$(curl -fsSL "${api_header[@]}" https://api.github.com/repos/actions/runner/releases/latest)"
asset_name="$(jq -r '.assets[] | select(.name | test("^actions-runner-linux-x64-[0-9.]+\\.tar\\.gz$")) | .name' <<<"$release_json")"
asset_url="$(jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url' <<<"$release_json")"
asset_digest="$(jq -r --arg name "$asset_name" '.assets[] | select(.name == $name) | .digest' <<<"$release_json")"
expected_sha="${asset_digest#sha256:}"
if [[ -z "$asset_name" || -z "$asset_url" || ! "$expected_sha" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Cannot resolve the signed Linux x64 runner asset and digest." >&2
  exit 1
fi

archive="/tmp/${asset_name}"
curl -fsSL -o "$archive" "$asset_url"
echo "${expected_sha}  ${archive}" | sha256sum --check --status
tar -xzf "$archive" -C "$INSTALL_ROOT"
rm -f "$archive"
chown -R "$RUNNER_USER:$RUNNER_USER" "$INSTALL_ROOT"

runuser -u "$RUNNER_USER" -- "$INSTALL_ROOT/config.sh" \
  --unattended \
  --url "$REPOSITORY_URL" \
  --token "$REGISTRATION_TOKEN" \
  --name "$RUNNER_NAME" \
  --labels "$LABELS" \
  --work _work

if [[ $cpu_role_count -eq 3 ]]; then
  runner_env="$INSTALL_ROOT/.env"
  filtered_env="$(mktemp)"
  if [[ -f "$runner_env" ]]; then
    grep -Ev '^REACTOR_BENCHMARK_(APPLICATION_CPU_SET|RUNNER_CPU_SET|COLLECTOR_CPU_SET)=' \
      "$runner_env" >"$filtered_env" || true
  fi
  cat >>"$filtered_env" <<EOF
REACTOR_BENCHMARK_APPLICATION_CPU_SET=$APPLICATION_CPUS
REACTOR_BENCHMARK_RUNNER_CPU_SET=$RUNNER_CPU
REACTOR_BENCHMARK_COLLECTOR_CPU_SET=$COLLECTOR_CPU
EOF
  install -o "$RUNNER_USER" -g "$RUNNER_USER" -m 0644 "$filtered_env" "$runner_env"
  rm -f "$filtered_env"
fi

(
  cd "$INSTALL_ROOT"
  ./svc.sh install "$RUNNER_USER"
  ./svc.sh start
)

echo "Runner installed: $RUNNER_NAME"
echo "Labels: self-hosted,linux,x64,$LABELS"
if [[ $cpu_role_count -eq 3 ]]; then
  echo "CPU roles: application=$APPLICATION_CPUS runner=$RUNNER_CPU collector=$COLLECTOR_CPU"
fi
echo "Do not install a second runner service on this host."
