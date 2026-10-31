#!/usr/bin/env python3
"""Minimal zwp_input_method_v2 client for testing text-input-v3 clients in sway: waits until a text field is activated,
shows a preedit string, then commits a string. Usage: wl_input_method.py [preedit] [commit]"""
import os, socket, struct, sys, time
pre = sys.argv[1] if len(sys.argv) > 1 else "ka"
com = sys.argv[2] if len(sys.argv) > 2 else "か✓"
s = socket.socket(socket.AF_UNIX)
s.connect(os.path.join(os.environ["XDG_RUNTIME_DIR"], os.environ["WAYLAND_DISPLAY"]))
nid = [1]; handlers = {}; inbuf = [b""]
def new(h=None):
    nid[0] += 1
    if h: handlers[nid[0]] = h
    return nid[0]
def pstr(t):
    b = t.encode() + b"\0"; b += b"\0" * (-len(b) % 4)
    return struct.pack("<I", len(t.encode()) + 1) + b
def req(o, op, body=b""): s.sendall(struct.pack("<II", o, ((8 + len(body)) << 16) | op) + body)
def pump(t=0.2):
    s.settimeout(t)
    try:
        d = s.recv(65536)
        if not d: sys.exit("closed")
        inbuf[0] += d
    except socket.timeout: pass
    while len(inbuf[0]) >= 8:
        o, sz = struct.unpack_from("<II", inbuf[0]); n = sz >> 16
        if len(inbuf[0]) < n: break
        body = inbuf[0][8:n]; inbuf[0] = inbuf[0][n:]
        if o in handlers: handlers[o](sz & 0xffff, body)
glob = {}
reg = new(lambda op, b: op == 0 and glob.__setitem__(b[8:8 + struct.unpack_from("<I", b, 4)[0] - 1].decode(), struct.unpack_from("<I", b)[0]))
req(1, 1, struct.pack("<I", reg))
d = []; cb = new(lambda op, b: d.append(1)); req(1, 0, struct.pack("<I", cb))
while not d: pump()
def bind(i, v, h=None):
    x = new(h); req(reg, 0, struct.pack("<I", glob[i]) + pstr(i) + struct.pack("<II", v, x)); return x
seat = bind("wl_seat", 5)
mgr = bind("zwp_input_method_manager_v2", 1)
state = {"active": False, "serial": 0, "pending": False, "done": 0}
def im_ev(op, b):
    if op == 0: state["pending"] = True; state["act"] = True
    elif op == 1: state["pending"] = True; state["act"] = False
    elif op == 5:
        state["done"] += 1; state["active"] = state.get("act", False); state["pending"] = False
im = new(im_ev); req(mgr, 0, struct.pack("<II", seat, im))
end = time.time() + 8
while not state["active"] and time.time() < end: pump(0.05)
print("activated:", state["active"])
if not state["active"]: sys.exit(1)
def commit(): req(im, 3, struct.pack("<I", state["done"]))
req(im, 1, pstr(pre) + struct.pack("<ii", 0, len(pre.encode()))); commit(); print("preedit sent"); time.sleep(0.8); pump(0.1)
req(im, 1, pstr("") + struct.pack("<ii", 0, 0)); req(im, 0, pstr(com)); commit(); print("commit sent"); time.sleep(0.8); pump(0.1)
