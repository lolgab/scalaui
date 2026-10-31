#!/usr/bin/env python3
"""Minimal XDND drag source for tests: drops the given file paths on the window titled argv[1] (needs python3-xlib + an X server)."""
import sys, time
from Xlib import X, display, Xatom
from Xlib.protocol import event

title, paths = sys.argv[1], sys.argv[2:]
d = display.Display(); root = d.screen().root
def find(w):
    try:
        n = w.get_wm_name()
        if n == title: return w
        for c in w.query_tree().children:
            r = find(c)
            if r: return r
    except Exception: pass
target = None
for _ in range(50):
    target = find(root)
    if target: break
    time.sleep(0.1)
assert target, "target window not found"
A = lambda n: d.intern_atom(n)
src = root.create_window(0, 0, 1, 1, 0, d.screen().root_depth)
uri = A("text/uri-list")
data = ("\r\n".join("file://" + p for p in paths) + "\r\n").encode()
src.set_selection_owner(A("XdndSelection"), X.CurrentTime)
def msg(typ, *vals):
    vals = list(vals) + [0] * (5 - len(vals))
    return event.ClientMessage(window=target, client_type=A(typ), data=(32, vals))
target.send_event(msg("XdndEnter", src.id, 5 << 24, uri.real if hasattr(uri,'real') else uri, 0, 0), event_mask=0)
d.flush(); time.sleep(0.2)
target.send_event(msg("XdndPosition", src.id, 0, (30 << 16) | 40, X.CurrentTime, A("XdndActionCopy")), event_mask=0)
d.flush(); time.sleep(0.3)
target.send_event(msg("XdndDrop", src.id, 0, X.CurrentTime), event_mask=0)
d.flush()
deadline = time.time() + 5; finished = False
while time.time() < deadline and not finished:
    while d.pending_events():
        e = d.next_event()
        if e.type == X.SelectionRequest:
            prop = e.property or e.target
            e.requestor.change_property(prop, e.target, 8, data)
            out = event.SelectionNotify(time=e.time, requestor=e.requestor, selection=e.selection, target=e.target, property=prop)
            e.requestor.send_event(out, event_mask=0)
            d.flush()
        elif e.type == X.ClientMessage and e.client_type == A("XdndFinished"):
            finished = True
    time.sleep(0.02)
print("finished" if finished else "timeout")
