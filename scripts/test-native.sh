#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_root="$(mktemp -d)"
trap 'rm -rf "$build_root"' EXIT
cd "$repo_root"
for suite in projection city_mesh synth plasticity vector_memory semantic_index cma_es dataset_decoder anokhin_gnw_core tsetlin_machine; do
  case "$suite" in
    synth) source=audio/synth ;;
    plasticity) source=mind/plasticity ;;
    vector_memory) source=mind/vector_memory ;;
    semantic_index) source=mind/semantic_index ;;
    cma_es) source=evolution/cma_es ;;
    dataset_decoder) source=research/dataset_decoder ;;
    anokhin_gnw_core|tsetlin_machine) source= ;;
    *) source="$suite" ;;
  esac
  sources=()
  if [[ -n "$source" ]]; then sources+=("app/src/main/cpp/$source.cpp"); fi
  extra=()
  if [[ "$suite" == vector_memory || "$suite" == semantic_index ]]; then extra=(-DHNSWLIB_ENABLE_EXCEPTIONS=ON -DHNSWLIB_USE_PREFETCH=0); fi
  if [[ "$suite" == dataset_decoder ]]; then extra+=(app/src/main/cpp/research/swap_arena.cpp); fi
  c++ "${extra[@]}" -std=c++20 -Wall -Wextra -Werror -fno-omit-frame-pointer -fsanitize=address,undefined \
    -Iapp/src/main/cpp -isystem app/src/main/cpp/third_party/hnswlib -isystem app/src/main/cpp/third_party/rapidjson/include \
    "${sources[@]}" "app/src/test/cpp/${suite}_test.cpp" -o "$build_root/$suite"
  "$build_root/$suite"
  printf '%s passed (ASan/UBSan)\n' "$suite"
done
c++ -std=c++20 -Wall -Wextra -Werror -fno-omit-frame-pointer -fsanitize=address,undefined \
  -DHNSWLIB_ENABLE_EXCEPTIONS=ON -DHNSWLIB_USE_PREFETCH=0 \
  -Iapp/src/main/cpp -isystem app/src/main/cpp/third_party/hnswlib \
  app/src/main/cpp/mind/vector_memory.cpp app/src/main/cpp/mind/semantic_index.cpp \
  app/src/test/cpp/memory_allocation_test.cpp -Wl,--wrap=malloc -Wl,--wrap=calloc -o "$build_root/memory_allocation"
"$build_root/memory_allocation"
printf '%s\n' 'memory allocation failures passed (ASan/UBSan)'
