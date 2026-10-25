#!/usr/bin/env bash
# Runs every module's tests one sbt invocation at a time (parallel `sbt test` races on Scala Native test processes and
# can run out of memory). A module that fails does not stop the others; the script exits non-zero if any failed.
# Tests that only apply to some OS skip themselves, so the same list runs everywhere.
#   tools/ci-test.sh                  # all modules
#   tools/ci-test.sh geom ui          # selected modules
set -u
cd "$(dirname "$0")/.."
MODULES=("$@")
if [ ${#MODULES[@]} -eq 0 ]; then
  MODULES=(geom deflate unicode brotli color raster codecs cms imaging font shaping text svg pdf docx clipboard gpu window ui)
fi
rm -f target/test-failures.log
failed=()
for m in "${MODULES[@]}"; do
  echo "::group::$m/testOnly"
  # --server: the thin client's background server would not see SCALAUI_* variables
  if ! sbt --server -J-Xmx6g "$m/testOnly *"; then failed+=("$m"); fi
  echo "::endgroup::"
done
if [ -f target/test-failures.log ]; then
  echo "::group::target/test-failures.log"; cat target/test-failures.log; echo "::endgroup::"
fi
if [ ${#failed[@]} -gt 0 ]; then echo "FAILED modules: ${failed[*]}"; exit 1; fi
echo "all modules passed"
