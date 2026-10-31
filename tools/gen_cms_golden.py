#!/usr/bin/env python3
"""Golden values for modules/cms tests, produced by littleCMS (`brew install little-cms2` -> transicc) on the
macOS system profiles. Output: modules/cms/src/test/resources/cms/golden.txt
line: <src.icc> <dst.icc> <intent> <bpc 0|1> <in...> | <out...>   (device values, 0..1 per channel)"""
import subprocess, itertools, random, os
P = "/System/Library/ColorSync/Profiles/"
SCALE = {"RGB": 255, "GRAY": 255, "CMYK": 100}
SPACE = {"sRGB Profile.icc": "RGB", "Display P3.icc": "RGB", "AdobeRGB1998.icc": "RGB", "Generic RGB Profile.icc": "RGB",
         "ITU-2020.icc": "RGB", "Generic Gray Profile.icc": "GRAY", "Generic Gray Gamma 2.2 Profile.icc": "GRAY",
         "Generic CMYK Profile.icc": "CMYK"}
random.seed(7)
def samples(space, n):
    k = {"RGB": 3, "GRAY": 1, "CMYK": 4}[space]
    pts = [tuple(float(v) for v in c) for c in itertools.product((0, 1), repeat=k)] if k < 4 else [(0,0,0,0),(1,1,1,1),(0,0,0,1),(1,0,0,0),(0,1,0,0),(0,0,1,0)]
    pts += [tuple(round(random.random(), 4) for _ in range(k)) for _ in range(n)]
    pts += [tuple([v] * k) for v in (0.1, 0.25, 0.5, 0.75)]
    return pts
pairs = [("sRGB Profile.icc", "Display P3.icc", 1, 0), ("Display P3.icc", "sRGB Profile.icc", 1, 0), ("AdobeRGB1998.icc", "sRGB Profile.icc", 1, 0),
         ("sRGB Profile.icc", "AdobeRGB1998.icc", 1, 0), ("Generic RGB Profile.icc", "sRGB Profile.icc", 0, 0), ("ITU-2020.icc", "sRGB Profile.icc", 1, 0),
         ("sRGB Profile.icc", "Generic RGB Profile.icc", 3, 0),
         ("Generic Gray Profile.icc", "sRGB Profile.icc", 1, 0), ("sRGB Profile.icc", "Generic Gray Profile.icc", 1, 0),
         ("Generic Gray Gamma 2.2 Profile.icc", "Display P3.icc", 0, 0),
         ("Generic CMYK Profile.icc", "sRGB Profile.icc", 0, 0), ("Generic CMYK Profile.icc", "sRGB Profile.icc", 1, 0),
         ("Generic CMYK Profile.icc", "sRGB Profile.icc", 1, 1), ("Generic CMYK Profile.icc", "Display P3.icc", 2, 0),
         ("sRGB Profile.icc", "Generic CMYK Profile.icc", 0, 0), ("sRGB Profile.icc", "Generic CMYK Profile.icc", 1, 0),
         ("AdobeRGB1998.icc", "Generic CMYK Profile.icc", 1, 0), ("sRGB Profile.icc", "AdobeRGB1998.icc", 1, 1)]
out = []
for s, d, intent, bpc in pairs:
    ss, ds = SPACE[s], SPACE[d]
    pts = samples(ss, 24)
    inp = "".join(" ".join("%.6f" % (v * SCALE[ss]) for v in p) + "\n" for p in pts)
    cmd = ["transicc", "-i" + P + s, "-o" + P + d, "-t%d" % intent, "-n", "-c0"] + (["-b"] if bpc else [])
    r = subprocess.run(cmd, input=inp, capture_output=True, text=True, check=True).stdout.strip().splitlines()
    r = [l for l in r if not l.startswith(("LittleCMS", "Copyright"))]
    assert len(r) == len(pts), (s, d, len(r), len(pts), r[:3])
    for p, l in zip(pts, r):
        o = [float(v) / SCALE[ds] for v in l.split()]
        out.append("%s|%s|%d|%d|%s|%s" % (s, d, intent, bpc, " ".join("%.5f" % v for v in p), " ".join("%.5f" % v for v in o)))
dest = os.path.join(os.path.dirname(__file__), "..", "modules/cms/src/test/resources/cms/golden.txt")
open(dest, "w").write("\n".join(out) + "\n")
print(len(out), "cases")
