#!/usr/bin/env python3
"""Compares ColorGlyphSuite output (target/color-out) with tools/color_glyph_ref.py references.
usage: color_glyph_compare.py <ref dir> <out dir> [max mean abs error 0..255 (default 6)] [-v]
Reports per glyph the share of pixels differing by more than 48/255 in any channel (premultiplied over white) and the mean error."""
import os, sys
from PIL import Image, ImageChops
ref, out = sys.argv[1], sys.argv[2]
tol = float(sys.argv[3]) if len(sys.argv) > 3 and not sys.argv[3].startswith('-') else 6.0
verbose = '-v' in sys.argv
def flat(p):
    im = Image.open(p).convert('RGBA'); bg = Image.new('RGBA', im.size, (255, 255, 255, 255)); bg.alpha_composite(im); return bg.convert('RGB')
bad = total = 0
for fn in sorted(os.listdir(ref)):
    o = os.path.join(out, fn)
    if not os.path.exists(o): print('MISSING', fn); bad += 1; total += 1; continue
    a, b = flat(os.path.join(ref, fn)), flat(o)
    d = ImageChops.difference(a, b); px = list(d.get_flattened_data()); n = len(px)
    mean = sum(sum(p) for p in px) / (3 * n)
    big = sum(1 for p in px if max(p) > 48) / n * 100
    total += 1
    ok = mean <= tol
    if not ok: bad += 1
    if verbose or not ok: print(('ok  ' if ok else 'FAIL'), fn, f'mean={mean:.2f} big={big:.2f}%')
print(f'{total - bad}/{total} within mean error {tol}')
sys.exit(1 if bad else 0)
