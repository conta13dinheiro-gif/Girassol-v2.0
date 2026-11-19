#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/libs/sherpa-onnx-1.10.41.aar"
TMP="$OUT.tmp"
mkdir -p "$(dirname "$OUT")"
if [[ -f "$OUT" ]] && echo '008a86c81432a616e23bb1c3e73aa807f294638fd02d267943f6e584aeffde9f  '"$OUT" | sha256sum --check --status; then
  exit 0
fi
rm -f "$TMP"
curl --fail --location --retry 4 --connect-timeout 20 --max-time 900 \
  "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-1.10.41.aar" \
  --output "$TMP"
echo '008a86c81432a616e23bb1c3e73aa807f294638fd02d267943f6e584aeffde9f  '"$TMP" | sha256sum --check
mv "$TMP" "$OUT"
