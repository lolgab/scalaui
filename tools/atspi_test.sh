#!/bin/bash
# Runs inside the Linux test container: Xvfb + private session bus + at-spi bus, then the demo and tools/atspi_check.py
set -e
Xvfb :9 -screen 0 1024x900x24 >/dev/null 2>&1 &
sleep 2
export DISPLAY=:9
dbus-run-session -- bash -c '
  /usr/libexec/at-spi-bus-launcher --launch-immediately >/dev/null 2>&1 &
  sleep 2
  cd /build/target/out/native0.5/scala-3.9.0/scalaui-examples/native
  SCALAUI_DEBUG=1 ./demo > /tmp/demo.log 2>&1 &
  sleep 4
  python3 /src/tools/atspi_check.py
  RC=$?
  tail -14 /tmp/demo.log
  exit $RC
'
