#!/usr/bin/env python3
"""Drag-and-drop source for testing Wayland drop targets in a wlroots compositor (sway), speaking the wire protocol
directly: opens a small xdg toplevel, moves a wlroots virtual pointer onto it, presses the button, starts a wl_data_device
drag offering text/uri-list (or the mime given with -m), carries it to the target position and releases.
Usage: wl_dnd_source.py [-m mime] [-p payload] SRC_X SRC_Y DST_X DST_Y   (absolute coordinates in the 1024x768 output)"""
import array, mmap, os, socket, struct, sys, time

args = sys.argv[1:]
mime = "text/uri-list"; payload = "file:///tmp/dropped%20file.txt\r\n"
while args and args[0] in ("-m", "-p"):
    if args[0] == "-m": mime = args[1]
    else: payload = args[1]
    args = args[2:]
sx, sy, dx, dy = map(int, args[:4])
W, H = 1024, 768

s = socket.socket(socket.AF_UNIX)
s.connect(os.path.join(os.environ["XDG_RUNTIME_DIR"], os.environ["WAYLAND_DISPLAY"]))
nid = [1]; handlers = {}; inbuf = b""; fds = []

def new(h=None):
    nid[0] += 1
    if h: handlers[nid[0]] = h
    return nid[0]

def pstr(t):
    b = t.encode() + b"\0"; b += b"\0" * (-len(b) % 4)
    return struct.pack("<I", len(t.encode()) + 1) + b

def req(obj, op, body=b"", fd=None):
    msg = struct.pack("<II", obj, ((8 + len(body)) << 16) | op) + body
    if fd is None: s.sendall(msg)
    else: s.sendmsg([msg], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array("i", [fd]))])

def pump(timeout=0.2):
    global inbuf
    s.settimeout(timeout)
    try:
        data, anc, _, _ = s.recvmsg(65536, socket.CMSG_LEN(16 * 4))
        for level, typ, d in anc:
            if level == socket.SOL_SOCKET and typ == socket.SCM_RIGHTS:
                a = array.array("i"); a.frombytes(d[:len(d) - len(d) % 4]); fds.extend(a)
        if not data: sys.exit("compositor closed the connection")
        inbuf += data
    except socket.timeout:
        pass
    while len(inbuf) >= 8:
        o, sz = struct.unpack_from("<II", inbuf); size = sz >> 16
        if len(inbuf) < size: break
        body = inbuf[8:size]; inbuf = inbuf[size:]
        h = handlers.get(o)
        if h: h(sz & 0xffff, body)

def wait(cond, secs=3):
    end = time.time() + secs
    while not cond() and time.time() < end: pump(0.05)
    if not cond(): sys.exit("timeout waiting for compositor")

glob = {}
reg = new(lambda op, b: op == 0 and glob.__setitem__(b[8:8 + struct.unpack_from("<I", b, 4)[0] - 1].decode(), (struct.unpack_from("<I", b)[0], )))
req(1, 1, struct.pack("<I", reg))
done = []; cb = new(lambda op, b: done.append(1)); req(1, 0, struct.pack("<I", cb)); wait(lambda: done)

def bind(iface, ver, h=None):
    i = new(h); req(reg, 0, struct.pack("<I", glob[iface][0]) + pstr(iface) + struct.pack("<II", ver, i)); return i

def sync():
    d = []; c = new(lambda op, b: d.append(1)); req(1, 0, struct.pack("<I", c)); wait(lambda: d)

comp = bind("wl_compositor", 4)
shm = bind("wl_shm", 1)
wm = bind("xdg_wm_base", 2, lambda op, b: op == 0 and req(wm, 3, b[:4]))
caps = []
seat = bind("wl_seat", 5, lambda op, b: op == 0 and caps.append(struct.unpack("<I", b[:4])[0]))
ddm = bind("wl_data_device_manager", 3)
vpm = bind("zwlr_virtual_pointer_manager_v1", 1)
vp = new(); req(vpm, 0, struct.pack("<II", 0, vp)); sync()

# window with a solid buffer
configured = []
surf = new()
req(comp, 0, struct.pack("<I", surf))
xs = new(lambda op, b: op == 0 and (req(xs, 4, b[:4]), configured.append(1)))
req(wm, 2, struct.pack("<II", xs, surf))
tl = new(); req(xs, 1, struct.pack("<I", tl))
req(tl, 2, pstr("dnd-src")); req(tl, 3, pstr("dnd-src"))
req(surf, 6); wait(lambda: configured)
w, h = 240, 240
fd = os.memfd_create("dnd"); os.ftruncate(fd, w * h * 4)
mm = mmap.mmap(fd, w * h * 4); mm.write(b"\x20\x80\xe0\xff" * (w * h))
pool = new(); req(shm, 0, struct.pack("<II", pool, w * h * 4), fd=fd)
buf = new(); req(pool, 0, struct.pack("<IiiiiI", buf, 0, w, h, w * 4, 0))
req(surf, 1, struct.pack("<Iii", buf, 0, 0)); req(surf, 6); sync()

# pointer
ptr_serial = []; btn_serial = []
def pointer_ev(op, b):
    if op == 0: ptr_serial.append(struct.unpack_from("<I", b)[0])
    if op == 3: btn_serial.append(struct.unpack_from("<I", b)[0])
wait(lambda: caps and caps[-1] & 1)
ptr = new(pointer_ev); req(seat, 0, struct.pack("<I", ptr)); sync()
t = [int(time.time() * 1000) & 0x7fffffff]
def tm(): t[0] += 10; return t[0]
def vmove(x, y): req(vp, 1, struct.pack("<IIIII", tm(), x, y, W, H)); req(vp, 4); sync()
time.sleep(0.3)
vmove(sx - 5, sy); vmove(sx, sy)
wait(lambda: ptr_serial)
req(vp, 2, struct.pack("<III", tm(), 0x110, 1)); req(vp, 4); sync()
wait(lambda: btn_serial)

# data source + drag
log = []
finished = []
def src_ev(op, b):
    if op == 0: log.append("target")
    elif op == 1:
        n = struct.unpack_from("<I", b)[0]; m = b[4:4 + n - 1].decode(); f = fds.pop(0)
        os.write(f, payload.encode()); os.close(f); log.append("send " + m)
    elif op == 2: log.append("cancelled"); finished.append(0)
    elif op == 3: log.append("drop_performed")
    elif op == 4: log.append("finished"); finished.append(1)
    elif op == 5: log.append("action %d" % struct.unpack_from("<I", b)[0])
src = new(src_ev); req(ddm, 0, struct.pack("<I", src))
req(src, 0, pstr(mime)); req(src, 2, struct.pack("<I", 1))
dev = new(); req(ddm, 1, struct.pack("<II", dev, seat))
req(dev, 0, struct.pack("<IIII", src, surf, 0, btn_serial[-1])); sync()
steps = 12
for i in range(1, steps + 1):
    vmove(sx + (dx - sx) * i // steps, sy + (dy - sy) * i // steps); time.sleep(0.05)
time.sleep(0.3)
req(vp, 2, struct.pack("<III", tm(), 0x110, 0)); req(vp, 4); sync()
end = time.time() + 3
while not finished and time.time() < end: pump(0.05)
print("source log:", ", ".join(log))
print("RESULT", "finished" if finished and finished[0] else "not-finished")
