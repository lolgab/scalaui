#!/bin/sh
# Compiles tools/shaders/*.vert|frag to SPIR-V resources (needs glslangValidator: brew install glslang).
set -e
cd "$(dirname "$0")"
for f in shaders/ui.vert shaders/ui.frag; do
  glslangValidator -V "$f" -o "../modules/gpu/src/main/resources/shaders/$(basename "$f").spv"
done
