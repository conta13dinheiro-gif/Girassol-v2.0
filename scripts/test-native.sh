#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_root="$(mktemp -d)"
trap 'rm -rf "$build_root"' EXIT
cd "$repo_root"
for suite in projection city_mesh synth plasticity vector_memory cma_es; do
  case "$suite" in synth) source=audio/synth ;; plasticity) source=mind/plasticity ;; vector_memory) source=mind/vector_memory ;; cma_es) source=evolution/cma_es ;; *) source="$suite" ;; esac
  extra=(); if [[ "$suite" == vector_memory ]]; then extra=(-fno-exceptions -DHNSWLIB_ENABLE_EXCEPTIONS=OFF -DHNSWLIB_USE_PREFETCH=0); fi
  c++ "${extra[@]}" -std=c++20 -Wall -Wextra -Werror -fno-omit-frame-pointer -fsanitize=address,undefined -Iapp/src/main/cpp -isystem app/src/main/cpp/third_party/hnswlib \
    "app/src/main/cpp/$source.cpp" "app/src/test/cpp/${suite}_test.cpp" -o "$build_root/$suite"
  "$build_root/$suite"
  printf '%s passed (ASan/UBSan)\n' "$suite"
done
