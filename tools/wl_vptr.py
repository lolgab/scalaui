#!/usr/bin/env python3
"""Injects pointer input into a wlroots compositor (sway) through zwlr_virtual_pointer_v1, speaking the Wayland wire protocol
directly. Usage: wl_vptr.py [-w W -h H] "move X Y" "down|up [button]" "click [button]" "scroll V" "sleep S" ...
Coordinates are absolute within the (W x H, default 1024x768) output extent."""
import os, socket, struct, sys, time

def main(args):
    W, H = 1024, 768
    while args and args[0] in ("-w", "-h"):
        if args[0] == "-w": W = int(args[1])
        else: H = int(args[1])
        args = args[2:]
    path = os.path.join(os.environ["XDG_RUNTIME_DIR"], os.environ["WAYLAND_DISPLAY"])
    s = socket.socket(socket.AF_UNIX); s.connect(path)
    nid = [1]
    def new(): nid[0] += 1; return nid[0]
    def pstr(t):
        b = t.encode() + b"\0"; b += b"\0" * (-len(b) % 4); return struct.pack("<I", len(b) if False else len(t.encode()) + 1) + b
    def req(obj, op, body=b""):
        s.sendall(struct.pack("<II", obj, ((8 + len(body)) << 16) | op) + body)
    def events():
        s.settimeout(2); data = b""
        try:
            while True:
                chunk = s.recv(65536)
                if not chunk: break
                data += chunk
                if len(chunk) < 65536: break
        except socket.timeout: pass
        out = []; i = 0
        while i + 8 <= len(data):
            o, sz = struct.unpack_from("<II", data, i); out.append((o, sz & 0xffff, data[i + 8:i + (sz >> 16)])); i += sz >> 16
        return out
    def sync():
        cb = new(); req(1, 0, struct.pack("<I", cb))
        while True:
            for o, op, b in events():
                if o == cb: return
    reg = new(); req(1, 1, struct.pack("<I", reg))
    gl = {}
    cb = new(); req(1, 0, struct.pack("<I", cb)); done = False
    while not done:
        for o, op, b in events():
            if o == reg and op == 0:
                name, n = struct.unpack_from("<II", b); iface = b[8:8 + n - 1].decode(); gl[iface] = name
            if o == cb: done = True
    mgr = new()
    name = gl["zwlr_virtual_pointer_manager_v1"]
    req(reg, 0, struct.pack("<I", name) + pstr("zwlr_virtual_pointer_manager_v1") + struct.pack("<II", 1, mgr))
    ptr = new(); req(mgr, 0, struct.pack("<II", 0, ptr)); sync()
    time.sleep(0.4)  # let the client notice the new pointer capability and bind wl_pointer before the first motion
    t = [int(time.time() * 1000) & 0x7fffffff]
    def tm(): t[0] += 10; return t[0]
    def frame(): req(ptr, 4); sync()
    for a in args:
        p = a.split()
        if p[0] == "move":
            req(ptr, 1, struct.pack("<IIIII", tm(), int(p[1]), int(p[2]), W, H)); frame()
        elif p[0] in ("down", "up", "click"):
            b = int(p[1], 0) if len(p) > 1 else 0x110
            if p[0] in ("down", "click"): req(ptr, 2, struct.pack("<III", tm(), b, 1)); frame()
            if p[0] == "click": time.sleep(0.05)
            if p[0] in ("up", "click"): req(ptr, 2, struct.pack("<III", tm(), b, 0)); frame()
        elif p[0] == "scroll":
            req(ptr, 3, struct.pack("<IIi", tm(), 0, int(float(p[1]) * 256))); frame()
        elif p[0] == "sleep": time.sleep(float(p[1]))
    req(ptr, 8); sync()

main(sys.argv[1:])
