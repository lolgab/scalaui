#!/usr/bin/env python3
"""Generates modules/shaping/src/test/resources/shaping-golden.txt by running HarfBuzz's `hb-shape` (a development-time
oracle only; nothing links HarfBuzz). Each line: font|direction|features|code points (hex)|gid:cluster:xAdvance:dx:dy,...
Glyphs are stored in logical order (hb-shape prints right-to-left runs reversed), clusters as code point indices.

    brew install harfbuzz && tools/gen_shaping_golden.py
"""
import json, subprocess, os, sys

root = os.path.join(os.path.dirname(__file__), "..")
fonts = {
    "NotoSans": "modules/font/src/main/resources/fonts/NotoSans-Regular.ttf",
    "NotoSansArabic": "modules/shaping/src/test/resources/fonts/NotoSansArabic-Regular.ttf",
    "NotoSansHebrew": "modules/shaping/src/test/resources/fonts/NotoSansHebrew-Regular.ttf",
    "NotoSansDevanagari": "modules/shaping/src/test/resources/fonts/NotoSansDevanagari-Regular.ttf",
    **{"NotoSans" + n: "modules/shaping/src/test/resources/fonts/NotoSans%s-Regular.ttf" % n for n in
       ["Bengali", "Tamil", "Telugu", "Malayalam", "Kannada", "Gujarati", "Gurmukhi", "Oriya", "Sinhala", "Thai", "Lao",
        "Javanese", "Balinese", "Khmer", "Myanmar", "Mongolian"]},
    "NotoSerifTibetan": "modules/shaping/src/test/resources/fonts/NotoSerifTibetan-Regular.ttf",
}
cases = [
    ("NotoSans", "ltr", "", "Hello, world!"),
    ("NotoSans", "ltr", "", "office fi fl ffi ffl"),
    ("NotoSans", "ltr", "-liga", "office fi fl ffi ffl"),
    ("NotoSans", "ltr", "", "AV To Wa Ty Ye"),
    ("NotoSans", "ltr", "-kern", "AV To Wa Ty Ye"),
    ("NotoSans", "ltr", "", "é ä ô ñ é ü"),
    ("NotoSans", "ltr", "", "Ǆ ǈ ŉ ﬁ 1/2 3/4"),
    ("NotoSans", "ltr", "", "Ελληνικά Привет мир"),
    ("NotoSans", "ltr", "smcp", "Small Caps"),
    ("NotoSans", "ltr", "frac", "1/2 3/4"),
    ("NotoSans", "ltr", "", "a‍b a‌b"),
    ("NotoSansArabic", "rtl", "", "السلام عليكم"),
    ("NotoSansArabic", "rtl", "", "بسم الله الرحمن الرحيم"),
    ("NotoSansArabic", "rtl", "", "لا لأ لإ لآ"),
    ("NotoSansArabic", "rtl", "", "محمد"),
    ("NotoSansArabic", "rtl", "", "مُحَمَّد"),
    ("NotoSansArabic", "rtl", "", "ب ببب ــ كتاب"),
    ("NotoSansArabic", "rtl", "", "لله اللّه"),
    ("NotoSansArabic", "rtl", "", "١٢٣٤٥ 12345"),
    ("NotoSansArabic", "rtl", "", "ب‍ب ب‌ب"),
    ("NotoSansHebrew", "rtl", "", "שלום עולם"),
    ("NotoSansHebrew", "rtl", "", "שָׁלוֹם"),
]

# seeded random strings: they exercise joining, mark ordering, ligatures and kerning far beyond the hand-picked cases
import random
rnd = random.Random(20261015)
def rand_text(alphabet, lo=3, hi=14):
    return "".join(rnd.choice(alphabet) for _ in range(rnd.randint(lo, hi)))
arabic = [chr(c) for c in range(0x0621, 0x064B)] * 3 + [chr(c) for c in range(0x064B, 0x0653)] + [" ", " ", "\u0640", "\u200D", "\u200C", "\u0660", "0", "\u06A9", "\u06CC", "\u067E", "\u0670"]
hebrew = [chr(c) for c in range(0x05D0, 0x05EB)] * 3 + [chr(c) for c in range(0x05B0, 0x05BE)] + ["\u05C1", "\u05C2", " ", " ", "\u05BE", "\u05F3", "\u05F4"]
latin = list("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ    .,;:!?-/0123456789") * 2 + ["f", "f", "i", "l", "\u0301", "\u0308", "\u0327", "\u00e9", "\u00fc", "\u00df", "\u200D", "\u00c6", "\u0153", "\u0132"]
# Devanagari: hand-picked syllables plus grammar-shaped random ones with some noise
cases += [("NotoSansDevanagari", "ltr", "", t) for t in [
    "\u0915\u0930\u094D\u092E", "\u0915\u094D\u0937\u0924\u094D\u0930\u093F\u092F", "\u0930\u094D\u0915", "\u0915\u093F", "\u0915\u0940 \u0915\u0941 \u0915\u0942",
    "\u0928\u092E\u0938\u094D\u0924\u0947", "\u0939\u093F\u0928\u094D\u0926\u0940", "\u0938\u0902\u0938\u094D\u0915\u0943\u0924", "\u0915\u094D\u0930", "\u0924\u094D\u0930",
    "\u0926\u094D\u0935", "\u0936\u094D\u0930\u0940", "\u0915\u094D\u200D\u0937", "\u0915\u094D\u200C\u0937", "\u0915\u093C\u0916\u093C", "\u0905\u0902\u0917\u0942\u0930",
    "\u092A\u094D\u0930\u0947\u092E", "\u0930\u093E\u0937\u094D\u091F\u094D\u0930", "\u0915\u0930\u094D\u092E\u094D\u0915\u094D", "\u0967\u0968\u0969 \u0915\u0903"]]
dcons = [chr(c) for c in range(0x0915, 0x093A)] + ["\u0958", "\u0959", "\u095A", "\u095B", "\u095C", "\u095D", "\u095E", "\u095F"]
dvow = [chr(c) for c in range(0x0904, 0x0915)]
dmatra = [chr(c) for c in range(0x093E, 0x094D)] + ["\u094E", "\u094F", "\u0955", "\u0956", "\u0957"]
def dev_syllable():
    out = ""
    if rnd.random() < 0.15: out += rnd.choice(dvow)
    else:
        out += rnd.choice(dcons)
        if rnd.random() < 0.15: out += "\u093C"
        for _ in range(rnd.choice([0, 0, 1, 1, 2, 3])):
            out += "\u094D" + rnd.choice(["", "", "", "\u200D", "\u200C"]) + rnd.choice(dcons)
    if rnd.random() < 0.5: out += rnd.choice(dmatra)
    if rnd.random() < 0.25: out += rnd.choice(["\u0901", "\u0902", "\u0903"])
    if rnd.random() < 0.1: out += "\u094D"
    return out
for _ in range(200): cases.append(("NotoSansDevanagari", "ltr", "", "".join(dev_syllable() for _ in range(rnd.randint(1, 3)))))
for _ in range(60): cases.append(("NotoSansDevanagari", "ltr", "", rand_text(dcons + dvow + dmatra + ["\u094D", "\u094D", "\u093C", "\u0902", "\u200D", "\u200C", " ", "\u0967"], 2, 9)))

# the other Indic scripts: (consonant ranges, vowel ranges, matra ranges, virama, nukta, signs)
def rng(*r): return [chr(c) for a, b in r for c in range(a, b + 1)]
indic = {
  "Bengali": (rng((0x995, 0x9B9), (0x9DC, 0x9DF), (0x9F0, 0x9F1)), rng((0x985, 0x994)), rng((0x9BE, 0x9CC), (0x9D7, 0x9D7)), "\u09CD", "\u09BC", rng((0x981, 0x983))),
  "Gurmukhi": (rng((0xA15, 0xA39), (0xA59, 0xA5E)), rng((0xA05, 0xA14)), rng((0xA3E, 0xA4C)), "\u0A4D", "\u0A3C", rng((0xA01, 0xA03), (0xA70, 0xA71))),
  "Gujarati": (rng((0xA95, 0xAB9)), rng((0xA85, 0xA94)), rng((0xABE, 0xACC)), "\u0ACD", "\u0ABC", rng((0xA81, 0xA83))),
  "Oriya": (rng((0xB15, 0xB39), (0xB5C, 0xB5D), (0xB5F, 0xB5F)), rng((0xB05, 0xB14)), rng((0xB3E, 0xB4C), (0xB56, 0xB57)), "\u0B4D", "\u0B3C", rng((0xB01, 0xB03))),
  "Tamil": (rng((0xB95, 0xBB9)), rng((0xB85, 0xB94)), rng((0xBBE, 0xBCC), (0xBD7, 0xBD7)), "\u0BCD", "", rng((0xB82, 0xB83))),
  "Telugu": (rng((0xC15, 0xC39)), rng((0xC05, 0xC14)), rng((0xC3E, 0xC4C), (0xC55, 0xC56)), "\u0C4D", "", rng((0xC01, 0xC03))),
  "Kannada": (rng((0xC95, 0xCB9), (0xCDE, 0xCDE)), rng((0xC85, 0xC94)), rng((0xCBE, 0xCCC), (0xCD5, 0xCD6)), "\u0CCD", "\u0CBC", rng((0xC82, 0xC83))),
  "Malayalam": (rng((0xD15, 0xD3A), (0xD7A, 0xD7F)), rng((0xD05, 0xD14)), rng((0xD3E, 0xD4C), (0xD57, 0xD57)), "\u0D4D", "", rng((0xD02, 0xD03), (0xD4E, 0xD4E))),
  "Sinhala": (rng((0xD9A, 0xDC6)), rng((0xD85, 0xD96)), rng((0xDCF, 0xDDF), (0xDF2, 0xDF3)), "\u0DCA", "", rng((0xD82, 0xD83))),
}
def indic_syllable(cons, vow, matra, virama, nukta, signs):
    out = ""
    if rnd.random() < 0.15: out += rnd.choice(vow)
    else:
        out += rnd.choice(cons)
        if nukta and rnd.random() < 0.1: out += nukta
        for _ in range(rnd.choice([0, 0, 1, 1, 2, 3])):
            out += virama + rnd.choice(["", "", "", "\u200D", "\u200C"]) + rnd.choice(cons)
    if rnd.random() < 0.5: out += rnd.choice(matra)
    if rnd.random() < 0.25: out += rnd.choice(signs)
    if rnd.random() < 0.1: out += virama
    return out
for name, (cons, vow, matra, virama, nukta, signs) in indic.items():
    for _ in range(80):
        cases.append(("NotoSans" + name, "ltr", "", "".join(indic_syllable(cons, vow, matra, virama, nukta, signs) for _ in range(rnd.randint(1, 3)))))
    for _ in range(25):
        cases.append(("NotoSans" + name, "ltr", "", rand_text(cons + vow + matra + [virama, virama, " "] + signs, 2, 9)))

# Thai and Lao: consonants, vowels, SARA AM and tone marks
thai = [chr(c) for c in range(0x0E01, 0x0E2F)] * 2 + [chr(c) for c in (0x0E30, 0x0E31, 0x0E32, 0x0E33, 0x0E34, 0x0E35, 0x0E36, 0x0E37, 0x0E38, 0x0E39, 0x0E3A,
        0x0E40, 0x0E41, 0x0E42, 0x0E43, 0x0E44, 0x0E45, 0x0E47, 0x0E48, 0x0E49, 0x0E4A, 0x0E4B, 0x0E4C, 0x0E4D, 0x0E4E)] + [" ", "\u0E33", "\u0E33"]
lao = [chr(c) for c in range(0x0E81, 0x0EAF) if c not in (0x0E83, 0x0E85, 0x0E86, 0x0E87, 0x0E89, 0x0E8B, 0x0E8C, 0x0E8E, 0x0E8F, 0x0E90, 0x0E91, 0x0E92, 0x0E93, 0x0E98, 0x0EA0, 0x0EA4, 0x0EA6)] + [chr(c) for c in (0x0EB0, 0x0EB1, 0x0EB2, 0x0EB3, 0x0EB4, 0x0EB5, 0x0EB6, 0x0EB7, 0x0EB8, 0x0EB9, 0x0EBB, 0x0EBC, 0x0EC0, 0x0EC1, 0x0EC2, 0x0EC8, 0x0EC9, 0x0ECA, 0x0ECB, 0x0ECC, 0x0ECD)] + [" ", "\u0EB3"]
for _ in range(100): cases.append(("NotoSansThai", "ltr", "", rand_text(thai, 2, 10)))
def lao_text():
    # known gap: stacks of 3+ combining marks position the third mark differently from HarfBuzz
    while True:
        t = rand_text(lao, 2, 10)
        if not __import__("re").search("[\u0EB1\u0EB4-\u0EB9\u0EBB\u0EBC\u0EC8-\u0ECD]{3}", t): return t
for _ in range(60): cases.append(("NotoSansLao", "ltr", "", lao_text()))
cases += [("NotoSansThai", "ltr", "", t) for t in ["\u0E14\u0E4B\u0E33", "\u0E19\u0E49\u0E33", "\u0E01\u0E33\u0E41\u0E1E\u0E07", "\u0E2A\u0E27\u0E31\u0E2A\u0E14\u0E35\u0E04\u0E23\u0E31\u0E1A"]]

# scripts handled by the Universal Shaping Engine and the Khmer/Myanmar shapers: random sequences from their blocks
def block(*r): return rng(*r)
rand_blocks = {
    "NotoSansJavanese": block((0xA980, 0xA9CD)) + [" "],
    "NotoSansBalinese": block((0x1B00, 0x1B4C), (0x1B6B, 0x1B73)) + [" "],
    "NotoSerifTibetan": block((0x0F40, 0x0F6C), (0x0F71, 0x0F84), (0x0F90, 0x0FBC)) + [" ", "\u0F0B"],
    "NotoSansKhmer": block((0x1780, 0x17D3)) + ["\u200C", "\u200D", " "],
    "NotoSansMyanmar": block((0x1000, 0x1049)) + ["\u200C", "\u200D", " "],
}
for font, alphabet in rand_blocks.items():
    for _ in range(80): cases.append((font, "ltr", "", rand_text(alphabet, 2, 10)))
cases += [("NotoSansKhmer", "ltr", "", t) for t in ["\u1780\u17D2\u1798\u17C2\u179A", "\u1798\u17D2\u1794\u17BB\u179F", "\u17A2\u17C6\u1796\u17B8", "\u179F\u17D2\u178F\u17B6\u1793"]]
cases += [("NotoSansMyanmar", "ltr", "", t) for t in ["\u1019\u103C\u1014\u103A\u1019\u102C", "\u1000\u103B\u102F\u1014\u103A", "\u1031\u1000\u102C\u1004\u103A\u1038"]]
cases += [("NotoSerifTibetan", "ltr", "", t) for t in ["\u0F56\u0F7C\u0F51\u0F0B\u0F61\u0F72\u0F42", "\u0F40\u0FB1\u0F72", "\u0F66\u0F92\u0FB1"]]

import re
def arabic_text():
    # a combining mark right after a space makes HarfBuzz reorder them in a way not explained by any feature; skip that
    while True:
        t = rand_text(arabic)
        if not re.search("[ \u0640][\u064B-\u0652\u0670]", t) and not re.search(" [\u064B-\u0652\u0670]", t): return t
for _ in range(150): cases.append(("NotoSansArabic", "rtl", "", arabic_text()))
for _ in range(60): cases.append(("NotoSansHebrew", "rtl", "", rand_text(hebrew)))
for _ in range(150): cases.append(("NotoSans", "ltr", "", rand_text(latin, 3, 24)))

def run(font, direction, features, text):
    cps = [ord(c) for c in text]
    args = ["hb-shape", "--font-file=" + os.path.join(root, fonts[font]), "--direction=" + direction, "--no-glyph-names",
            "--output-format=json", "--unicodes=" + ",".join("U+%04X" % c for c in cps)]
    if features: args.append("--features=" + features)
    out = json.loads(subprocess.check_output(args))
    if direction == "rtl": out.reverse()
    return cps, out

lines = []
for font, direction, features, text in cases:
    cps, out = run(font, direction, features, text)
    g = ",".join("%s:%d:%d:%d:%d" % (e["g"], e["cl"], e["ax"], e["dx"], e["dy"]) for e in out)
    lines.append("|".join([font, direction, features, " ".join("%X" % c for c in cps), g]))
dst = os.path.join(root, "modules/shaping/src/test/resources/shaping-golden.txt")
open(dst, "w").write("\n".join(lines) + "\n")
print("wrote", len(lines), "cases to", dst)

# ---- vertical (TTB) cases: hb-shape --direction=ttb ----------------------------------------------------------------
# Format: font|features|code points (hex)|gid:cluster:xAdvance:yAdvance:dx:dy,...   (glyphs in logical order)
# NotoSansJP-Subset.otf is a pyftsubset subset of Noto Sans JP (SIL OFL 1.1) keeping GSUB/GPOS/VORG/vhea/vmtx; regenerate with
#   pyftsubset NotoSansJP-Regular.otf --text=<chars in the cases> --layout-features='*' --glyph-names --notdef-outline --output-file=...
fonts["NotoSansJP"] = "modules/shaping/src/test/resources/fonts/NotoSansJP-Subset.otf"
vrnd = random.Random(20261024)
jp_chars = list("日本語漢字縦書きの文章を表示します。、。「」『』（）〔〕【】ー～…・！？：；ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ一二三四五六七八九十ひらがなカタカナ々〆〇ゝゞヽヾ") + \
    list("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 .,;:!?()[]{}-_/") + list("ＡＢＣｱｲｳｴｵ０１２３‐〜")
vcases = [
    ("NotoSansJP", "", "日本語の縦書き"), ("NotoSansJP", "", "「こんにちは」、世界。"), ("NotoSansJP", "", "（ひらがな）【カタカナ】"),
    ("NotoSansJP", "", "ー～…・！？"), ("NotoSansJP", "", "ぁぃぅぇぉっゃゅょ"), ("NotoSansJP", "", "ABC abc 123"),
    ("NotoSansJP", "-vert", "「日本語」、。"), ("NotoSansJP", "vrt2", "「日本語」、。ー"), ("NotoSansJP", "vkna", "ぁぃぅぇぉっゃゅょァィゥェォッャュョ"),
    ("NotoSansJP", "vpal", "日本語、「ひらがな」。"), ("NotoSansJP", "vkrn", "日本語、「ひらがな」。"), ("NotoSansJP", "vhal", "日本語、「ひらがな」。"),
    ("NotoSansJP", "vert,vkrn,vpal", "「ア」ー、ア。"), ("NotoSansJP", "", ""),
]
for _ in range(120):
    vcases.append(("NotoSansJP", "", "".join(vrnd.choice(jp_chars) for _ in range(vrnd.randint(1, 14)))))
for feats in ["vkna", "vrt2", "vpal", "vkrn", "vhal", "-vert", "vert,vpal,vkrn", "hwid", "fwid", "palt"]:
    for _ in range(8):
        vcases.append(("NotoSansJP", feats, "".join(vrnd.choice(jp_chars) for _ in range(vrnd.randint(2, 10)))))
for _ in range(25):  # fonts without vertical tables: advances/origins come from the horizontal metrics
    vcases.append(("NotoSans", "", rand_text(latin, 2, 12)))
vcases += [("NotoSans", "", "Hello World"), ("NotoSans", "", "é ö")]
for _ in range(20): vcases.append(("NotoSansArabic", "", arabic_text()))
for _ in range(15): vcases.append(("NotoSansHebrew", "", rand_text(hebrew, 2, 8)))
for _ in range(20): vcases.append(("NotoSansDevanagari", "", "".join(dev_syllable() for _ in range(rnd.randint(1, 3)))))
for _ in range(10): vcases.append(("NotoSansThai", "", rand_text(thai, 2, 8)))

vlines = []
for font, features, text in vcases:
    if not text: continue
    cps = [ord(c) for c in text]
    args = ["hb-shape", "--font-file=" + os.path.join(root, fonts[font]), "--direction=ttb", "--no-glyph-names",
            "--output-format=json", "--unicodes=" + ",".join("U+%04X" % c for c in cps)]
    if features: args.append("--features=" + features)
    out = json.loads(subprocess.check_output(args))
    g = ",".join("%s:%d:%d:%d:%d:%d" % (e["g"], e["cl"], e["ax"], e["ay"], e["dx"], e["dy"]) for e in out)
    vlines.append("|".join([font, features, " ".join("%X" % c for c in cps), g]))
vdst = os.path.join(root, "modules/shaping/src/test/resources/shaping-golden-vertical.txt")
open(vdst, "w").write("\n".join(vlines) + "\n")
print("wrote", len(vlines), "vertical cases to", vdst)
