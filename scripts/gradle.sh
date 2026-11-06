#!/usr/bin/env bash
# Runs Gradle with build output and caches kept outside the checkout.
# Usage: scripts/gradle.sh [--online] <gradle args...>
#   XIAOMAN_TOOLCHAIN  optional env script to source first (sets JAVA_HOME, ANDROID_HOME, ...)
#   XIAOMAN_BUILD_DIR  out-of-tree build root
#                       (default: <toolchain dir>/builds/<checkout hash>/out if XIAOMAN_TOOLCHAIN is set,
#                       else ${XDG_CACHE_HOME:-~/.cache}/xiaoman/<checkout hash>/out)
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sha1() { if command -v sha1sum >/dev/null; then sha1sum; else shasum -a 1; fi; }
key="$(printf '%s' "$here" | sha1 | cut -c1-12)"
toolchain="${XIAOMAN_TOOLCHAIN:-}"
if [[ -n "$toolchain" ]]; then
  base="$(dirname "$toolchain")/builds/$key"
  # shellcheck disable=SC1090
  source "$toolchain"
else
  base="${XDG_CACHE_HOME:-$HOME/.cache}/xiaoman/$key"
fi
offline=(--offline)
if [[ "${1:-}" == "--online" ]]; then offline=(); shift; fi
export XIAOMAN_BUILD_DIR="${XIAOMAN_BUILD_DIR:-$base/out}"
mkdir -p "$XIAOMAN_BUILD_DIR" "$base/project-cache" "$base/kotlin"
gradle_bin="$(command -v gradle || true)"
if [[ -x "$here/gradlew" && -f "$here/gradle/wrapper/gradle-wrapper.jar" ]]; then gradle_bin="$here/gradlew"; fi
exec "$gradle_bin" -p "$here" "${offline[@]}" \
  --project-cache-dir "$base/project-cache" \
  -Pkotlin.project.persistent.dir="$base/kotlin" \
  "$@"
