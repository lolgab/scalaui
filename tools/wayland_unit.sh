#!/bin/bash
# Runs the Wayland-dependent window tests against headless sway inside the Linux test container.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p $XDG_RUNTIME_DIR; chmod 700 $XDG_RUNTIME_DIR
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman WLR_RENDERER_ALLOW_SOFTWARE=1
printf 'default_border none\noutput HEADLESS-1 resolution 1024x768\n' > /tmp/sway.conf
sway -c /tmp/sway.conf >/tmp/sway.log 2>&1 &
for i in $(seq 1 50); do ls $XDG_RUNTIME_DIR/wayland-* >/dev/null 2>&1 && break; sleep 0.2; done
export WAYLAND_DISPLAY=$(basename $(ls $XDG_RUNTIME_DIR/wayland-* | grep -v lock | head -1))
export SWAYSOCK=$(ls $XDG_RUNTIME_DIR/sway-ipc.* | head -1)
export SCALAUI_WAYLAND_TESTS=1 SCALAUI_DEBUG=1
# the headless compositor has no input devices, so it never gives keyboard focus (needed for selections) until a virtual keyboard exists
wtype -s 900000 >/dev/null 2>&1 &
sleep 1
cd /build && rm -f target/test-failures.log
sbt --server -Dsbt.color=false -J-Xmx3g "window/testOnly scalaui.window.${1:-WaylandSuite}" exit > /tmp/sbt-wl.log 2>&1
grep "wl:\|Passed\|Failed" /tmp/sbt-wl.log | head -40; tail -3 /tmp/sbt-wl.log
cat target/test-failures.log 2>/dev/null | head -30
kill %1 %2 2>/dev/null
