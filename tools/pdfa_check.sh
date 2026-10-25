#!/bin/bash
# Validates PDF/A output of modules/pdf with veraPDF (brew install verapdf). Writes every PdfSuite scene as PDF/A-2b and PDF/A-2u
# (PDFA env var) and runs the matching veraPDF profile on each file; exits non-zero on any violation.
# usage: tools/pdfa_check.sh [--no-build]   (set SBT to override the sbt command)
set -u
cd "$(dirname "$0")/.."
sbt="${SBT:-sbt --server -J-Xmx8g}"
status=0
for level in 2b 2u; do
  dir="target/pdfa-$level"
  rm -rf "$dir"
  if [ "${1:-}" != "--no-build" ]; then
    PDFA=$level PDF_OUT="$dir" $sbt "pdf/testOnly *" > "target/pdfa-$level.log" 2>&1 || { echo "pdf tests failed, see target/pdfa-$level.log"; exit 1; }
  fi
  for f in "$dir"/*.pdf; do
    out=$(verapdf --flavour "$level" "$f" 2>&1)
    if echo "$out" | grep -q 'isCompliant="true"'; then echo "PASS PDF/A-$level $(basename "$f")"
    else
      echo "FAIL PDF/A-$level $(basename "$f")"; status=1
      echo "$out" | grep -E '<description>|errorMessage' | sed 's/^ */    /' | sort | uniq | head -10
    fi
  done
done
exit $status
