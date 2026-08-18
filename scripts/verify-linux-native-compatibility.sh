#!/usr/bin/env bash

set -euo pipefail

artifact="${1:?usage: verify-linux-native-compatibility.sh <artifact> [maximum-glibc-version]}"
maximum_glibc="${2:-2.17}"

if [[ ! -f "$artifact" ]]; then
  echo "Native artifact does not exist: $artifact" >&2
  exit 1
fi

if ! file "$artifact" | grep -q 'ELF 64-bit LSB shared object, x86-64'; then
  echo "Expected an x86-64 ELF shared object: $artifact" >&2
  file "$artifact" >&2
  exit 1
fi

required_versions="$({ readelf -W --version-info "$artifact" || true; } \
  | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)*' \
  | sed 's/^GLIBC_//' \
  | sort -Vu)"

if [[ -z "$required_versions" ]]; then
  echo "No GLIBC symbol versions were found in $artifact" >&2
  exit 1
fi

highest_required="$(printf '%s\n' "$required_versions" | tail -n 1)"
highest_allowed="$(printf '%s\n%s\n' "$highest_required" "$maximum_glibc" | sort -Vu | tail -n 1)"
if [[ "$highest_allowed" != "$maximum_glibc" ]]; then
  echo "GLIBC compatibility violation: $artifact requires $highest_required; maximum allowed is $maximum_glibc" >&2
  printf 'Required symbol versions:\n%s\n' "$required_versions" >&2
  exit 1
fi

if ldd "$artifact" 2>&1 | grep -q 'not found'; then
  echo "Unresolved native dependency in $artifact" >&2
  ldd "$artifact" >&2
  exit 1
fi

echo "Native compatibility OK: $artifact requires GLIBC <= $highest_required (policy <= $maximum_glibc)."
