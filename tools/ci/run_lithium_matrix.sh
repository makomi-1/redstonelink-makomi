#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/ci/run_lithium_matrix.sh [options]

Options:
  --run-client   Also run no-lithium and with-lithium runClient smoke tests.
  -h, --help     Show this help.
EOF
}

run_client=false

for arg in "$@"; do
  case "$arg" in
    --run-client) run_client=true ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      usage
      exit 2
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
cd "$repo_root"

echo "[LithiumMatrix] Step 1/2: no lithium -> compileJava + remapJar"
chmod +x ./gradlew
./gradlew --no-daemon compileJava remapJar

echo "[LithiumMatrix] Step 2/2: with lithium -> compileJava + remapJar"
./gradlew -PwithLithium=true --no-daemon compileJava remapJar

if $run_client; then
  echo "[LithiumMatrix] Smoke: no lithium -> runClient"
  ./gradlew --no-daemon runClient

  echo "[LithiumMatrix] Smoke: with lithium -> runClient"
  ./gradlew -PwithLithium=true --no-daemon runClient
fi

echo "[LithiumMatrix] All checks passed."
