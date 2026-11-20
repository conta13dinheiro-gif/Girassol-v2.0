#!/usr/bin/env bash
# Sourced by host validation: vendor warnings remain visible; Girassol code keeps -Werror.
compile_navigation() {
  nav_objects=()
  nav_includes=(-isystem app/src/main/cpp/third_party/recastnavigation/Recast/Include -isystem app/src/main/cpp/third_party/recastnavigation/Detour/Include)
  local source object
  for source in app/src/main/cpp/third_party/recastnavigation/Recast/Source/*.cpp app/src/main/cpp/third_party/recastnavigation/Detour/Source/*.cpp; do
    object="$build_root/$(basename "${source%.cpp}").o"
    c++ -std=c++20 -O1 -Wall -Wextra -fPIC -ffp-contract=off -fno-fast-math "${nav_includes[@]}" "$@" -c "$source" -o "$object"
    nav_objects+=("$object")
  done
}
