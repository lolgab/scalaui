#!/usr/bin/env python3
"""Walks the AT-SPI tree like a screen reader and exercises the scalaui demo: prints findings, exits non-zero on failure.
Needs python3-gi + gir1.2-atspi-2.0 and a running session/accessibility bus (see tools/atspi_test.sh)."""
import sys, time, gi
gi.require_version("Atspi", "2.0")
from gi.repository import Atspi

def walk(node, depth=0, out=None):
    out = [] if out is None else out
    out.append((depth, node))
    for i in range(node.get_child_count()):
        walk(node.get_child_at_index(i), depth + 1, out)
    return out

app = None
for _ in range(50):
    desktop = Atspi.get_desktop(0)
    for i in range(desktop.get_child_count()):
        a = desktop.get_child_at_index(i)
        if a and a.get_name().startswith("scalaui"):
            app = a
    if app: break
    time.sleep(0.2)
assert app, "scalaui application not found on the accessibility bus"
print("app:", app.get_name(), "role:", app.get_role_name())
nodes = walk(app)
for d, n in nodes[:14]:
    print("  " * d, n.get_role_name(), repr(n.get_name()))
button = next((n for _, n in nodes if n.get_role_name() == "push button" and n.get_name() == "Click me"), None)
assert button, "button 'Click me' missing"
ext = button.get_component_iface().get_extents(Atspi.CoordType.WINDOW)
print("button extents:", ext.x, ext.y, ext.width, ext.height)
assert ext.width > 20 and ext.height > 10
assert button.get_state_set().contains(Atspi.StateType.ENABLED)
checks = [n for _, n in nodes if n.get_role_name() == "check box"]
assert checks and checks[0].get_state_set().contains(Atspi.StateType.CHECKED), "checkbox state"
entry = next((n for _, n in nodes if n.get_role_name() == "entry"), None)
assert entry, "text entry missing"
got = Atspi.Text.get_text(entry, 0, -1)
print("entry text:", repr(got), "chars:", Atspi.Text.get_character_count(entry))
assert got == "Ferris"

# --- Text interface: caret, selection, boundaries, extents, editable text, signals ---
from gi.repository import GLib
events = []
def on_event(ev, *_): events.append((ev.type, ev.detail1, ev.detail2, ev.any_data))
listener = Atspi.EventListener.new(on_event)
for kind in ("object:text-changed:insert", "object:text-changed:delete", "object:text-caret-moved", "object:text-selection-changed"):
    Atspi.EventListener.register(listener, kind)
def pump(seconds=1.0):
    ctx = GLib.MainContext.default(); end = time.time() + seconds
    while time.time() < end:
        while ctx.pending(): ctx.iteration(False)
        time.sleep(0.02)
iface = entry.get_interfaces()
print("entry interfaces:", iface)
assert "Text" in iface and "EditableText" in iface, "Text/EditableText interfaces missing"
assert entry.get_state_set().contains(Atspi.StateType.EDITABLE), "EDITABLE state"
assert entry.get_state_set().contains(Atspi.StateType.SINGLE_LINE), "SINGLE_LINE state"
assert Atspi.Text.get_caret_offset(entry) == 6, "caret at end of 'Ferris'"
assert Atspi.Text.get_n_selections(entry) == 0
assert Atspi.Text.get_character_at_offset(entry, 1) == ord("e")
assert Atspi.Text.get_text(entry, 2, 4) == "rr"
word = Atspi.Text.get_text_at_offset(entry, 3, Atspi.TextBoundaryType.WORD_START)
assert word.content == "Ferris" and word.start_offset == 0 and word.end_offset == 6, (word.content, word.start_offset, word.end_offset)
line = Atspi.Text.get_string_at_offset(entry, 2, Atspi.TextGranularity.LINE)
assert line.content == "Ferris", line.content
ext = Atspi.Text.get_character_extents(entry, 0, Atspi.CoordType.WINDOW)
print("char extents:", ext.x, ext.y, ext.width, ext.height)
assert ext.width > 0 and ext.height > 0
# selection set from the assistive technology reaches the widget and comes back
assert Atspi.Text.set_selection(entry, 0, 1, 4), "SetSelection refused"
pump(1.0)
assert Atspi.Text.get_n_selections(entry) == 1, "selection did not stick"
sel = Atspi.Text.get_selection(entry, 0)
print("selection:", sel.start_offset, sel.end_offset)
assert (sel.start_offset, sel.end_offset) == (1, 4)
assert Atspi.Text.set_caret_offset(entry, 2)
pump(1.0)
assert Atspi.Text.get_caret_offset(entry) == 2 and Atspi.Text.get_n_selections(entry) == 0
# editable text: replace, insert, delete; the text-changed signals follow
events.clear()
assert Atspi.EditableText.set_text_contents(entry, "Ferris!"), "SetTextContents refused"
pump(1.5)
assert Atspi.Text.get_text(entry, 0, -1) == "Ferris!", Atspi.Text.get_text(entry, 0, -1)
assert Atspi.EditableText.insert_text(entry, 0, ">", 1)
pump(1.5)
assert Atspi.Text.get_text(entry, 0, -1) == ">Ferris!"
assert Atspi.EditableText.delete_text(entry, 0, 1)
pump(1.5)
assert Atspi.Text.get_text(entry, 0, -1) == "Ferris!"
print("signals:", events)
kinds = [e[0] for e in events]
assert any(k.startswith("object:text-changed:insert") for k in kinds), "no text-changed:insert signal"
assert any(k.startswith("object:text-changed:delete") for k in kinds), "no text-changed:delete signal"
assert any(k.startswith("object:text-caret-moved") for k in kinds), "no text-caret-moved signal"
ins = next(e for e in events if e[0].startswith("object:text-changed:insert"))
assert ins[3] in ("!", ">"), ins
print("TEXT OK")
Atspi.EventListener.deregister(listener, "object:text-changed:insert")
action = button.get_action_iface()
assert action.get_n_actions() == 1
assert action.do_action(0), "DoAction failed"
time.sleep(1.5)
nodes = walk(app)
labels = [n.get_name() for _, n in nodes if n.get_role_name() == "label"]
print("labels after press:", [l for l in labels if "clicked" in l])
assert any("clicked 1 times" in l for l in labels), "press did not reach the app"
print("ATSPI OK")
