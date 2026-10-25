#!/usr/bin/env python3
"""Compares our SVG renders against reference PNGs (resvg's test suite or local resvg output) with ImageMagick.

usage: svg_compare.py <corpus dir with .svg + .png> <our output dir> [filter] [--fuzz=10] [--max=0.01] [--only=list.txt]
Prints per-directory pass counts and the worst failures. A render passes when fewer than `max` of the pixels differ by more than `fuzz` percent.
"""
import concurrent.futures as cf, os, subprocess, sys, collections

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    opts = dict(a[2:].split("=") for a in sys.argv[1:] if a.startswith("--") and "=" in a)
    corpus, out = args[0], args[1]
    flt = args[2] if len(args) > 2 else ""
    only = set(l.strip() for l in open(opts["only"])) if "only" in opts else None
    fuzz = opts.get("fuzz", "10"); maxfrac = float(opts.get("max", "0.01"))
    items = []
    for root, _, files in os.walk(corpus):
        for f in files:
            if f.endswith(".svg") and flt in os.path.join(root, f):
                rel = os.path.relpath(os.path.join(root, f), corpus)[:-4]
                ref = os.path.join(corpus, rel + ".png")
                if os.path.exists(ref) and (only is None or rel in only): items.append((rel, ref, os.path.join(out, rel + ".png")))
    def one(it):
        rel, ref, mine = it
        if not os.path.exists(mine): return rel, None, "missing"
        try:
            ident = lambda p: subprocess.run(["magick", "identify", "-format", "%w %h", p], capture_output=True, text=True).stdout.split()
            a, b = ident(ref), ident(mine)
            if a != b: return rel, 1.0, f"size {a} vs {b}"
            r = subprocess.run(["magick", "compare", "-metric", "AE", "-fuzz", fuzz + "%", ref, mine, "null:"], capture_output=True, text=True)
            ae = float(r.stderr.split()[0])
            return rel, ae / (int(a[0]) * int(a[1])), ""
        except Exception as e:
            return rel, None, str(e)
    with cf.ThreadPoolExecutor(8) as ex: res = list(ex.map(one, items))
    byDir = collections.defaultdict(lambda: [0, 0])
    fails = []
    for rel, frac, note in res:
        d = os.path.dirname(rel)
        byDir[d][1] += 1
        if frac is not None and frac <= maxfrac: byDir[d][0] += 1
        else: fails.append((frac if frac is not None else 2.0, rel, note))
    tp = sum(v[0] for v in byDir.values()); tt = sum(v[1] for v in byDir.values())
    for d in sorted(byDir): print(f"{d:50s} {byDir[d][0]:4d}/{byDir[d][1]:4d}")
    print(f"TOTAL {tp}/{tt}")
    fails.sort(reverse=True)
    with open(os.path.join(out, "failures.txt"), "w") as f:
        for fr, rel, note in fails: f.write(f"{fr:.4f} {rel} {note}\n")
if __name__ == "__main__": main()
