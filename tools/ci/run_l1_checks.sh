#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: tools/ci/run_l1_checks.sh [options]

Options:
  --strict-clean       Fail if workspace is dirty before running checks.
  --skip-gradle        Skip Gradle task chain.
  --skip-drift-check   Skip git status drift check.
  -h, --help           Show this help.
EOF
}

strict_clean=false
skip_gradle=false
skip_drift_check=false

for arg in "$@"; do
  case "$arg" in
    --strict-clean) strict_clean=true ;;
    --skip-gradle) skip_gradle=true ;;
    --skip-drift-check) skip_drift_check=true ;;
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

if $strict_clean && [[ -n "$(git status --porcelain)" ]]; then
  echo "Workspace is dirty but --strict-clean is enabled." >&2
  git status --short
  exit 1
fi

before_file="$(mktemp)"
after_file="$(mktemp)"
trap 'rm -f "$before_file" "$after_file"' EXIT

git status --porcelain | sort > "$before_file"

if ! $skip_gradle; then
  chmod +x ./gradlew
  ./gradlew --no-daemon clean compileJava processResources validateAccessWidener test runDatagen
fi

if ! $skip_drift_check; then
  git status --porcelain | sort > "$after_file"
  if ! diff -u "$before_file" "$after_file" > /dev/null; then
    echo "Detected workspace status drift after checks:" >&2
    diff -u "$before_file" "$after_file" || true
    exit 1
  fi
fi

echo "L1 checks passed."
