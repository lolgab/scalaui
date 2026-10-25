#!/bin/bash
# Renders an SVG corpus with scalaui into $2 (usage: run_svg_corpus.sh <corpus dir> <out dir> [name filter] [fonts dir])
set -e
cd "$(dirname "$0")/.."
export SVG_WIDTH="${SVG_WIDTH:-300}" SVG_CORPUS="$1" SVG_OUT="$2" SVG_FILTER="${3:-}" SVG_FONTS="${4:-}"
sbt --server -J-Xmx8g "svg/testOnly *CorpusSuite"
