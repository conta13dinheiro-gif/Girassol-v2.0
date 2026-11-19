#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
matc_bin="${FILAMENT_TOOLS_DIR:?Set FILAMENT_TOOLS_DIR to the Filament v1.56.0 directory}/bin/matc"
if [ "$("$matc_bin" --version)" != 56 ]; then
  echo 'Expected Filament v1.56.0 matc (material version 56).' >&2
  exit 1
fi
cd "$repo_root"
for source in app/src/main/materials/*.mat; do
  material="$(basename "$source" .mat)"
  "$matc_bin" -p mobile -a opengl -o "app/src/main/assets/materials/$material.filamat" "app/src/main/materials/$material.mat"
done
sha256sum app/src/main/materials/*.mat app/src/main/assets/materials/*.filamat > app/src/main/materials/city.sha256
