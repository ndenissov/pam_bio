#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_FLAGS=()
for arg in "$@"; do
  case "$arg" in
    --offline)
      GRADLE_FLAGS+=(--offline)
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--offline]" >&2
      exit 2
      ;;
  esac
done

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

echo "=== [1/3] Go daemon checks (vet, -race tests, Linux & Windows builds) ==="
(
  cd "$ROOT_DIR/daemon"
  go vet ./...
  GOOS=windows go vet ./...
  go test -race -count=1 ./...
  go build -o "$TMP_DIR/pambiod" ./cmd/pambiod
  GOOS=windows go build -o "$TMP_DIR/pambiod.exe" ./cmd/pambiod
  if command -v golangci-lint >/dev/null 2>&1; then
    golangci-lint run ./...
  else
    echo "INFO: golangci-lint not found, skipping optional linter"
  fi
)

echo "=== [2/3] PAM C module build check ==="
if [ -f /usr/include/security/pam_appl.h ]; then
  make -C "$ROOT_DIR/pam" clean all
  make -C "$ROOT_DIR/pam" clean
else
  echo "INFO: /usr/include/security/pam_appl.h not present on host; skipping C module compilation"
fi

echo "=== [3/3] Android JVM tests, test compilation, lint, and APK build ==="
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  for candidate in \
    /nix/store/*android-studio*/jbr \
    /opt/android-studio/jbr \
    /usr/lib/jvm/java-17-openjdk* \
    /usr/lib/jvm/default; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

if [ -n "${JAVA_HOME:-}" ]; then
  echo "Using JAVA_HOME=$JAVA_HOME"
fi

(
  cd "$ROOT_DIR/android"
  ./gradlew "${GRADLE_FLAGS[@]}" \
    testDebugUnitTest \
    compileDebugAndroidTestKotlin \
    lintDebug \
    assembleDebug \
    --console=plain
)

echo "ALL CHECKS PASSED"

