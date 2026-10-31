#!/bin/bash
# Runs inside the Linux test container: headless sway (wlroots) + the demo as a Wayland client, once with the Vulkan
# backend (lavapipe) and once with the software backend; pointer input through tools/wl_vptr.py (wlroots virtual pointer),
# keyboard through wtype, screenshots through grim go to /out. The two renderings are compared with ImageMagick.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p $XDG_RUNTIME_DIR; chmod 700 $XDG_RUNTIME_DIR
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman WLR_RENDERER_ALLOW_SOFTWARE=1
printf 'default_border none\noutput HEADLESS-1 resolution 1024x768\n' > /tmp/sway.conf
sway -c /tmp/sway.conf >/tmp/sway.log 2>&1 &
for i in $(seq 1 50); do ls $XDG_RUNTIME_DIR/wayland-* >/dev/null 2>&1 && break; sleep 0.2; done
export WAYLAND_DISPLAY=$(basename $(ls $XDG_RUNTIME_DIR/wayland-* | grep -v lock | head -1))
export SWAYSOCK=$(ls $XDG_RUNTIME_DIR/sway-ipc.* | head -1)
cd /build/target/out/native0.5/scala-3.9.0/scalaui-examples/native
mkdir -p /out
for mode in vulkan software; do
  if [ $mode = software ]; then export SCALAUI_SOFTWARE=1; else unset SCALAUI_SOFTWARE; fi
  echo "=== $mode"
  SCALAUI_DEBUG=1 ./demo > /tmp/demo-$mode.log 2>&1 &
  sleep 5
  grep "gpu backend" /tmp/demo-$mode.log | head -1
  swaymsg -t get_tree | grep -o '"app_id": "[^"]*"' | head -1
  grim /out/$mode-idle.png
  wtype -s 600000 >/dev/null 2>&1 & # keeps a virtual keyboard (and so keyboard focus) alive between the typing commands
  sleep 0.5
  python3 $(dirname $0)/wl_vptr.py "move 47 190" "click" "sleep 0.3" "move 160 138" "click" 2>&1 | head -3
  sleep 0.5
  wtype -d 50 "hi é"; sleep 0.5
  wtype -k Left; wtype -k BackSpace; wtype -M ctrl a -m ctrl; sleep 1
  grim /out/$mode-typed.png
  grep "^events" /tmp/demo-$mode.log | grep -v "MouseMoved(.*)\$" | head -12
  grep -ci "exception\|error" /tmp/demo-$mode.log
  pkill -x demo; pkill -x wtype; sleep 1
done
for s in idle typed; do
  echo "compare $s (AE of channels differing >4%, of $((1024*768)) px):"
  compare -metric AE -fuzz 4% /out/vulkan-$s.png /out/software-$s.png /out/diff-$s.png 2>&1; echo
done
