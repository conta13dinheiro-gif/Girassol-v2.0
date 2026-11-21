#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/libs/sherpa-onnx-2.0.1.aar"
TMP="$OUT.tmp"
mkdir -p "$(dirname "$OUT")"
if [[ -f "$OUT" ]] && echo 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  '"$OUT" | sha256sum --check --status; then
  exit 0
fi
rm -f "$TMP"
curl --fail --location --retry 4 --connect-timeout 20 --max-time 900 \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar" \
  --output "$TMP"
echo 'a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7  '"$TMP" | sha256sum --check
mv "$TMP" "$OUT"
