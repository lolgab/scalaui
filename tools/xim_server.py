#!/usr/bin/env python3
"""A minimal XIM (X Input Method) server for testing scalaui's XIM client; needs python3-xlib and an X display.

It speaks the real protocol (selection discovery through XIM_SERVERS, _XIM_XCONNECT, CONNECT, OPEN, QUERY_EXTENSION,
ENCODING_NEGOTIATION, GET_IM_VALUES, CREATE_IC, SET_IC_VALUES, SET_IC_FOCUS, FORWARD_EVENT, COMMIT, SYNC, PREEDIT_*)
and implements a tiny input method:

  grave, then a vowel   -> committed accented letter (a -> a-grave, e -> e-grave, ...), composing through the preedit
                           callbacks ("`" is shown as preedit text in between)
  F9                    -> commits a fixed string with a non-Latin-1 character (U+4F60 U+597D)
  anything else         -> handed back with FORWARD_EVENT (not consumed)

Options: --name NAME (server name, default testxim), --encoding utf8|ct (which text encoding is offered),
--style callbacks|position|nothing (offered input styles), --sync (ask for synchronous event flow),
--log FILE (append one line per protocol action, used by the tests), --need-focus.
"""
import argparse, struct, sys, time
from Xlib import X, display, Xatom, protocol

ap = argparse.ArgumentParser()
ap.add_argument("--name", default="testxim")
ap.add_argument("--encoding", default="utf8", choices=["utf8", "ct"])
ap.add_argument("--style", default="callbacks", choices=["callbacks", "position", "nothing"])
ap.add_argument("--sync", action="store_true")
ap.add_argument("--need-focus", action="store_true")
ap.add_argument("--log", default="")
args = ap.parse_args()

d = display.Display()
root = d.screen().root
logf = open(args.log, "a", buffering=1) if args.log else None
def log(*a):
    line = " ".join(str(x) for x in a)
    if logf: logf.write(line + "\n")
    print("xim_server:", line, file=sys.stderr, flush=True)

A = lambda n: d.intern_atom(n)
sel = A("@server=" + args.name)
t_proto, t_more, t_xconn = A("_XIM_PROTOCOL"), A("_XIM_MOREDATA"), A("_XIM_XCONNECT")
t_locales, t_transport = A("LOCALES"), A("TRANSPORT")
win = root.create_window(0, 0, 1, 1, 0, d.screen().root_depth)
win.set_selection_owner(sel, X.CurrentTime)
root.change_property(A("XIM_SERVERS"), Xatom.ATOM, 32, [sel], X.PropModeAppend)
d.flush()
log("ready", args.name)

# ---- attribute tables (name, id, type) as a real server would advertise them
IM_ATTRS = [("queryInputStyle", 0, 10)]
IC_ATTRS = [("inputStyle", 0, 3), ("clientWindow", 1, 5), ("focusWindow", 2, 5), ("filterEvents", 3, 3),
            ("preeditAttributes", 4, 0x7fff), ("statusAttributes", 5, 0x7fff), ("area", 6, 11), ("areaNeeded", 7, 11),
            ("spotLocation", 8, 12), ("foreground", 9, 3), ("background", 10, 3)]
IC_NAME = {i: n for n, i, _ in IC_ATTRS}
STYLES = {"callbacks": [0x2 | 0x400, 0x2 | 0x800], "position": [0x4 | 0x400, 0x4 | 0x800], "nothing": [0x8 | 0x400]}[args.style]

def pad(n): return (4 - n % 4) % 4
def u16(b, o): return struct.unpack_from("<H", b, o)[0]
def u32(b, o): return struct.unpack_from("<I", b, o)[0]
def i32(b, o): return struct.unpack_from("<i", b, o)[0]

def attr_list(attrs):
    out = b""
    for name, i, t in attrs:
        nb = name.encode()
        e = struct.pack("<HHH", i, t, len(nb)) + nb
        e += b"\0" * pad(len(e))
        out += e
    return out

def encode(text):
    """Text in the negotiated encoding: UTF-8, or Compound Text (Latin-1 as is, others as ESC % G UTF-8 segments)."""
    if encoding == "utf8": return text.encode()
    out = b""; seg = ""
    def flush():
        nonlocal out, seg
        if seg: out += b"\x1b%G" + seg.encode() + b"\x1b%@"; seg = ""
    for ch in text:
        if ord(ch) < 256: flush(); out += bytes([ord(ch)])
        else: seg += ch
    flush()
    return out

class Client:
    def __init__(self, win_id, srv):
        self.win_id = srv.id          # our per-client communication window
        self.srv = srv
        self.win = d.create_resource_object("window", win_id)   # the client's window
        self.imid = 1
        self.ics = {}       # icid -> dict
        self.next_ic = 1
        self.partial = b""
        self.props = 0

clients = {}
encoding = "utf8"

def send(cl, op, body=b"", minor=0):
    body += b"\0" * pad(len(body))
    pkt = bytes([op, minor]) + struct.pack("<H", len(body) // 4) + body
    if len(pkt) <= 20:
        data = pkt + b"\0" * (20 - len(pkt))
        ev = protocol.event.ClientMessage(window=cl.win, client_type=t_proto, data=(8, data))
    else:
        cl.props += 1
        atom = A("_server%d" % (cl.props % 64))
        cl.win.change_property(atom, Xatom.STRING, 8, pkt, X.PropModeAppend)
        ev = protocol.event.ClientMessage(window=cl.win, client_type=t_proto, data=(32, (len(pkt), atom, 0, 0, 0)))
    cl.win.send_event(ev, event_mask=0)
    d.flush()

def keysym_of(ev):
    keycode = ev[1]; state = u16(ev, 28)
    shifted = bool(state & 1)
    return d.keycode_to_keysym(keycode, 1 if shifted else 0), state

VOWELS = {"a": "à", "e": "è", "i": "ì", "o": "ò", "u": "ù"}

def preedit_draw(cl, ic, text):
    t = encode(text)
    n = len(t)
    body = struct.pack("<HHiiiI", cl.imid, ic["id"], len(text), 0, ic["preedit_len"], 0 if text else 1)
    body += struct.pack("<H", n) + t
    body += b"\0" * pad(2 + n)
    fb = [1] * len(text)   # reverse
    body += struct.pack("<HH", 4 * len(fb), 0) + b"".join(struct.pack("<I", f) for f in fb)
    send(cl, 75, body)
    ic["preedit_len"] = len(text)

def commit(cl, ic, text):
    t = encode(text)
    n = len(t)
    flag = 2 | (1 if args.sync else 0)
    body = struct.pack("<HHHH", cl.imid, ic["id"], flag, n) + t
    send(cl, 63, body)
    log("commit", text)

def forward_back(cl, ic, ev):
    send(cl, 60, struct.pack("<HHHH", cl.imid, ic["id"], 0, u16(ev, 2)) + ev)
    log("forward-back", keysym_of(ev)[0])

def on_key(cl, ic, ev, release):
    if release:
        forward_back(cl, ic, ev); return
    ks, state = keysym_of(ev)
    callbacks = ic["style"] & 0x2
    if args.need_focus and not ic["focus"]:
        log("key-without-focus"); return
    if ic["dead"]:
        ic["dead"] = False
        ch = VOWELS.get(chr(ks) if 0 < ks < 128 else "")
        if callbacks:
            preedit_draw(cl, ic, "")
            send(cl, 78, struct.pack("<HH", cl.imid, ic["id"]))
        if ch: commit(cl, ic, ch)
        else:
            if chr(ks) == "`" or ks == 0x60: commit(cl, ic, "`")
            else: forward_back(cl, ic, ev)
        return
    if ks == 0x60:  # grave: start composing
        ic["dead"] = True
        log("compose-start")
        if callbacks:
            send(cl, 73, struct.pack("<HH", cl.imid, ic["id"]))
            ic["preedit_len"] = 0
            preedit_draw(cl, ic, "`")
        return
    if ks == 0xffc6:  # F9
        commit(cl, ic, "你好"); return
    forward_back(cl, ic, ev)

def handle(cl, op, body):
    global encoding
    if op == 1:   # CONNECT
        send(cl, 2, struct.pack("<HH", 1, 0))
        log("connect")
    elif op == 30:  # OPEN
        n = body[0]; log("open", body[1:1 + n].decode())
        send(cl, 31, struct.pack("<HH", cl.imid, len(attr_list(IM_ATTRS))) + attr_list(IM_ATTRS) +
             struct.pack("<HH", len(attr_list(IC_ATTRS)), 0) + attr_list(IC_ATTRS))
    elif op == 40:
        send(cl, 41, struct.pack("<HH", cl.imid, 0)); log("query-extension")
    elif op == 38:  # ENCODING_NEGOTIATION
        n = u16(body, 2); names = []; p = 4
        while p < 4 + n:
            l = body[p]; names.append(body[p + 1:p + 1 + l].decode()); p += 1 + l
        want = "UTF-8" if args.encoding == "utf8" else "COMPOUND_TEXT"
        idx = names.index(want) if want in names else -1
        encoding = args.encoding if idx >= 0 else "ct"
        log("encoding", names, "->", idx)
        send(cl, 39, struct.pack("<HHhH", cl.imid, 0, idx, 0))
    elif op == 44:  # GET_IM_VALUES
        n = u16(body, 2); ids = [u16(body, 4 + 2 * i) for i in range(n // 2)]
        out = b""
        for i in ids:
            if i == 0:
                v = struct.pack("<HH", len(STYLES), 0) + b"".join(struct.pack("<I", s) for s in STYLES)
                out += struct.pack("<HH", 0, len(v)) + v + b"\0" * pad(len(v))
        send(cl, 45, struct.pack("<HH", cl.imid, len(out)) + out)
    elif op == 50:  # CREATE_IC
        n = u16(body, 2); p = 4; attrs = {}
        while p < 4 + n:
            aid, l = u16(body, p), u16(body, p + 2)
            attrs[IC_NAME.get(aid, aid)] = body[p + 4:p + 4 + l]
            p += 4 + l + pad(l)
        icid = cl.next_ic; cl.next_ic += 1
        style = u32(attrs["inputStyle"], 0)
        ic = {"id": icid, "style": style, "dead": False, "focus": False, "preedit_len": 0}
        cl.ics[icid] = ic
        log("create-ic style=0x%x client=0x%x" % (style, u32(attrs["clientWindow"], 0)), "has-spot" if "preeditAttributes" in attrs else "")
        send(cl, 51, struct.pack("<HH", cl.imid, icid))
        # static event flow: forward key press + release; optionally ask for synchronous handling
        send(cl, 37, struct.pack("<HHII", cl.imid, icid, 3, 3 if args.sync else 0))
    elif op == 54:  # SET_IC_VALUES
        icid = u16(body, 2); n = u16(body, 4); p = 8
        while p < 8 + n:
            aid, l = u16(body, p), u16(body, p + 2)
            if IC_NAME.get(aid) == "preeditAttributes":
                q = p + 4
                while q < p + 4 + l:
                    a2, l2 = u16(body, q), u16(body, q + 2)
                    if IC_NAME.get(a2) == "spotLocation":
                        x, y = struct.unpack_from("<hh", body, q + 4); log("spot %d %d" % (x, y))
                    q += 4 + l2 + pad(l2)
            p += 4 + l + pad(l)
        send(cl, 55, struct.pack("<HH", cl.imid, icid))
    elif op in (58, 59):
        ic = cl.ics.get(u16(body, 2))
        if ic is not None: ic["focus"] = op == 58
        log("focus" if op == 58 else "unfocus")
    elif op == 60:  # FORWARD_EVENT
        icid = u16(body, 2); flag = u16(body, 4); ev = body[8:40]
        ic = cl.ics.get(icid)
        if ic is not None:
            on_key(cl, ic, ev, (ev[0] & 0x7f) == 3)
        if flag & 1:
            send(cl, 62, struct.pack("<HH", cl.imid, icid))   # synchronous: acknowledge
    elif op == 62:
        pass
    elif op == 61:
        send(cl, 62, body[:4])
    elif op == 52:
        send(cl, 53, body[:2] + b"\0\0"); log("destroy-ic")
    elif op == 32:
        send(cl, 33, body[:2] + b"\0\0"); log("close")
    elif op == 3:
        send(cl, 4, b""); log("disconnect"); clients.pop(cl.win_id, None)
    elif op in (74, 77):
        pass
    else:
        log("unhandled", op)

while True:
    e = d.next_event()
    if e.type == X.SelectionRequest:
        txt = None
        if e.target == t_locales: txt = b"@locale=en_US.UTF-8,C"
        elif e.target == t_transport: txt = b"@transport=X/"
        prop = e.property
        if txt is not None:
            e.requestor.change_property(prop, Xatom.STRING, 8, txt)
        notify = protocol.event.SelectionNotify(time=e.time, requestor=e.requestor, selection=e.selection, target=e.target,
                                                property=prop if txt is not None else X.NONE)
        e.requestor.send_event(notify, event_mask=0)
        d.flush()
    elif e.type == X.ClientMessage:
        t = e.client_type
        if t == t_xconn:
            cw = e.data[1][0]
            srv = root.create_window(0, 0, 1, 1, 0, d.screen().root_depth)
            cl = clients[srv.id] = Client(cw, srv)
            ev = protocol.event.ClientMessage(window=cl.win, client_type=t_xconn, data=(32, (srv.id, 0, 0, 20, 0)))
            cl.win.send_event(ev, event_mask=0); d.flush()
        elif t in (t_proto, t_more):
            cl = clients.get(e.window.id)
            if cl is None: continue
            fmt, data = e.data
            if fmt == 8:
                raw = bytes(data) if not isinstance(data, str) else data.encode("latin-1")
                cl.partial += raw
                if t == t_proto:
                    total = 4 + 4 * u16(cl.partial, 2)
                    msg = cl.partial[:total]; cl.partial = b""
                    handle(cl, msg[0], msg[4:])
            else:
                n, atom = data[0], data[1]
                r = cl.srv.get_full_property(atom, Xatom.STRING)
                cl.srv.delete_property(atom)
                raw = bytes(r.value) if r else b""
                raw = raw[:n]
                handle(cl, raw[0], raw[4:])
