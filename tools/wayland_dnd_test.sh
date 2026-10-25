#!/bin/bash
# Drag-and-drop end to end in the Linux test container: the demo is the drop target, tools/wl_dnd_source.py the source.
export LANG=C.UTF-8 LC_ALL=C.UTF-8
export XDG_RUNTIME_DIR=/tmp/xdg; mkdir -p $XDG_RUNTIME_DIR; chmod 700 $XDG_RUNTIME_DIR
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman WLR_RENDERER_ALLOW_SOFTWARE=1
printf 'default_border none\noutput HEADLESS-1 resolution 1024x768\n' > /tmp/sway.conf
sway -c /tmp/sway.conf >/tmp/sway.log 2>&1 &
for i in $(seq 1 50); do ls $XDG_RUNTIME_DIR/wayland-* >/dev/null 2>&1 && break; sleep 0.2; done
export WAYLAND_DISPLAY=$(basename $(ls $XDG_RUNTIME_DIR/wayland-* | grep -v lock | head -1))
export SWAYSOCK=$(ls $XDG_RUNTIME_DIR/sway-ipc.* | head -1)
cd /build/target/out/native0.5/scala-3.9.0/scalaui-examples/native
SCALAUI_DEBUG=1 ./demo > /tmp/demo-dnd.log 2>&1 &
sleep 4
echo "== uri-list drag from a second client onto the demo (left half)"
python3 $(dirname $0)/wl_dnd_source.py 768 384 250 300
sleep 1
grep -h "FilesH\|FilesD" /tmp/demo-dnd.log | head -8
echo "== text/plain drag (must be declined, no events)"
python3 $(dirname $0)/wl_dnd_source.py -m text/plain -p hello 768 384 250 300
grep -c "FilesDropped" /tmp/demo-dnd.log
grep -i "exception\|protocol error" /tmp/demo-dnd.log | head -3
pkill -x demo
