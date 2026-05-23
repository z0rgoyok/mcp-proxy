#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v qodana >/dev/null 2>&1; then
  echo "qodana CLI is not installed. Run: brew install jetbrains/utils/qodana" >&2
  exit 1
fi

cd "$ROOT_DIR"

QODANA_CACHE_DIR="${QODANA_CACHE_DIR:-$ROOT_DIR/build/qodana-cache}"

if [[ -z "${JAVA_HOME:-}" && -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export GRADLE_OPTS="-Dorg.gradle.java.home=$JAVA_HOME ${GRADLE_OPTS:-}"
fi

qodana scan \
  -i "$ROOT_DIR" \
  -l qodana-jvm-community \
  --within-docker=false \
  --config "$ROOT_DIR/qodana.yaml" \
  --cache-dir "$QODANA_CACHE_DIR" \
  --results-dir "$ROOT_DIR/build/qodana-results" \
  --report-dir "$ROOT_DIR/build/qodana-report" \
  --print-problems \
  --disable-update-checks
