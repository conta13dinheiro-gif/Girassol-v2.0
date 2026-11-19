#!/usr/bin/env bash
set -euo pipefail
# Native host smoke test. Pass a compatible local GGUF; no weights are downloaded by this script.
if [ "$#" -ne 1 ]; then
  echo 'Usage: scripts/test-cognition.sh /absolute/path/model.gguf' >&2
  exit 2
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
model_path="$(realpath "$1")"
build_root="$(mktemp -d)"
trap 'rm -rf "$build_root"' EXIT
cmake -S "$repo_root/app/src/test/cpp/cognition" -B "$build_root" -DCMAKE_BUILD_TYPE=Release
cmake --build "$build_root" -j2
"$build_root/cognition_test" "$model_path"
