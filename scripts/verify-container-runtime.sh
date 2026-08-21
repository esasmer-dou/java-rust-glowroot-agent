#!/usr/bin/env sh
set -eu

APP_HOME="${1:?usage: verify-container-runtime.sh APP_HOME VERSION [ADAPTER] [NATIVE_PATH]}"
VERSION="${2:?usage: verify-container-runtime.sh APP_HOME VERSION [ADAPTER] [NATIVE_PATH]}"
ADAPTER="${3:-}"
NATIVE_PATH="${4:-${APP_HOME}/glowroot/librust_glowroot_agent.so}"
CLASSPATH_INDEX="${APP_HOME}/BOOT-INF/classpath.idx"

fail() {
  echo "Glowroot container gate failed: $*" >&2
  exit 1
}

require_artifact() {
  artifact="$1"
  file_name="${artifact}-${VERSION}.jar"
  grep -Fq "${file_name}" "${CLASSPATH_INDEX}" \
    || fail "${file_name} is absent from ${CLASSPATH_INDEX}"
  jar_path="$(find "${APP_HOME}" -type f -name "${file_name}" -print -quit)"
  [ -n "${jar_path}" ] || fail "${file_name} is referenced but missing from the final container"
  echo "verified ${jar_path}"
}

[ -f "${CLASSPATH_INDEX}" ] || fail "missing ${CLASSPATH_INDEX}"
require_artifact "java-rust-glowroot-spring-boot-starter"
require_artifact "java-rust-glowroot-spring-runtime"

case "${ADAPTER}" in
  "") ;;
  tomcat|jetty|undertow)
    require_artifact "java-rust-glowroot-spring-${ADAPTER}-adapter"
    ;;
  *) fail "adapter must be tomcat, jetty, undertow, or empty" ;;
esac

[ -f "${NATIVE_PATH}" ] || fail "missing native library ${NATIVE_PATH}"
if command -v ldd >/dev/null 2>&1; then
  ldd_output="$(ldd "${NATIVE_PATH}" 2>&1)" || fail "ldd could not inspect ${NATIVE_PATH}: ${ldd_output}"
  echo "${ldd_output}" | grep -q 'not found' \
    && fail "${NATIVE_PATH} has an unresolved native dependency"
fi

echo "Glowroot container runtime gate passed: version=${VERSION} adapter=${ADAPTER:-none}"
