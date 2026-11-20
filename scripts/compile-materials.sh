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
  "$matc_bin" -p mobile -a opengl -a vulkan -o "app/src/main/assets/materials/$material.filamat" "app/src/main/materials/$material.mat"
  "$FILAMENT_TOOLS_DIR/bin/matinfo" "app/src/main/assets/materials/$material.filamat" | python3 -c '
import re,sys
text=sys.stdin.read()
chunks={key:int(size) for key,size in re.findall(r"(MAT_GLSL|MAT_SPIR)\s+(\d+)",text)}
assert chunks.get("MAT_GLSL",0)>0 and chunks.get("MAT_SPIR",0)>0, "Material lacks OpenGL or Vulkan shaders"
print("Verified OpenGL/Vulkan material chunks:", chunks)
'
done
sha256sum app/src/main/materials/*.mat app/src/main/assets/materials/*.filamat > app/src/main/materials/city.sha256
