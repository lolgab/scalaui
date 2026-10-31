#!/usr/bin/env python3
"""Generates modules/unicode/.../Tables.scala from the Unicode Character Database.

    tools/gen_unicode.py <ucd-dir>      # directory holding the UCD files (LineBreak.txt, Scripts.txt, ...)

Every property becomes a partition of 0..10FFFF: ascending range starts plus one value index per range, packed into
ASCII strings (4 chars of 6 bits per number) so no class-file constant or array initializer limit is hit.
"""
import re, sys, os

ucd = sys.argv[1]
out = os.path.join(os.path.dirname(__file__), "../modules/unicode/src/main/scala/scalaui/unicode/Tables.scala")
MAX = 0x10FFFF
ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

def read(name):
    return open(os.path.join(ucd, name), encoding="utf-8").read().splitlines()

def parse_ranges(name, field=1, filt=None):
    """Yield (lo, hi, value) from `lo[..hi] ; value # ...` lines."""
    for line in read(name):
        line = line.split("#")[0].strip()
        if not line: continue
        parts = [p.strip() for p in line.split(";")]
        if filt and not filt(parts): continue
        r = parts[0].split("..")
        lo = int(r[0], 16); hi = int(r[-1], 16)
        yield lo, hi, parts[field]

def partition(ranges, default, extra=()):
    """ranges -> (names, starts, values) covering 0..MAX, later ranges override earlier ones."""
    names = [default]
    idx = {default: 0}
    for e in extra:
        if e not in idx: idx[e] = len(names); names.append(e)
    cps = []
    for lo, hi, v in ranges:
        if v not in idx: idx[v] = len(names); names.append(v)
        cps.append((lo, hi, idx[v]))
    cps.sort(key=lambda t: t[0])
    # one byte per code point (1.1 MB) is fine for a generator; later ranges win
    flat = bytearray(MAX + 1)
    for lo, hi, i in cps: flat[lo:hi + 1] = bytes([i]) * (hi - lo + 1)
    starts, vals = [], []
    prev = -1
    for cp in range(MAX + 1):
        if flat[cp] != prev:
            starts.append(cp); vals.append(flat[cp]); prev = flat[cp]
    return names, starts, vals

def enc(nums):
    s = []
    for n in nums:
        assert 0 <= n < (1 << 24)
        s.append(ALPHA[(n >> 18) & 63] + ALPHA[(n >> 12) & 63] + ALPHA[(n >> 6) & 63] + ALPHA[n & 63])
    return "".join(s)

def scala_name(v):
    n = re.sub(r"\W", "_", v)
    return "_" + n if n[0].isdigit() else n

objs = []
def emit(obj, names, starts, vals, comment):
    body = enc(starts) + enc(vals)
    chunks = [body[i:i + 8000] for i in range(0, len(body), 8000)]
    consts = "\n".join(f"  inline val {scala_name(n)} = {i}" for i, n in enumerate(names))
    nm = ", ".join(f'"{n}"' for n in names)
    ch = ",\n    ".join(f'"{c}"' for c in chunks)
    objs.append(f"""/** {comment} */
object {obj}:
{consts}
  val names: Array[String] = Array({nm})
  val table: RangeTable = RangeTable({len(starts)}, Array(
    {ch}))
""")

# --- General_Category
gc = list(parse_ranges("DerivedGeneralCategory.txt"))
emit("Gc", *partition(gc, "Cn"), "General_Category")
# --- East_Asian_Width (only the lines of EastAsianWidth.txt)
emit("Eaw", *partition(parse_ranges("EastAsianWidth.txt"), "N"), "East_Asian_Width")
# --- Line_Break
emit("Lb", *partition(parse_ranges("LineBreak.txt"), "XX"), "Line_Break")
# --- Grapheme_Cluster_Break
emit("Gcb", *partition(parse_ranges("GraphemeBreakProperty.txt"), "Other"), "Grapheme_Cluster_Break")
# --- Word_Break
emit("Wb", *partition(parse_ranges("WordBreakProperty.txt"), "Other"), "Word_Break")
# --- Extended_Pictographic
emit("ExtPict", *partition(parse_ranges("emoji-data.txt", 1, lambda p: p[1] == "Extended_Pictographic"), "No"), "Extended_Pictographic (value Extended_Pictographic)")
# --- Indic_Conjunct_Break
incb = []
for line in read("DerivedCoreProperties.txt"):
    line = line.split("#")[0].strip()
    if not line: continue
    p = [x.strip() for x in line.split(";")]
    if len(p) == 3 and p[1] == "InCB":
        r = p[0].split(".."); incb.append((int(r[0], 16), int(r[-1], 16), p[2]))
emit("InCB", *partition(incb, "None"), "Indic_Conjunct_Break")
# --- Default_Ignorable_Code_Point
emit("DefaultIgnorable", *partition([(lo, hi, "Yes") for lo, hi, v in parse_ranges("DerivedCoreProperties.txt", 1, lambda p: len(p) == 2 and p[1] == "Default_Ignorable_Code_Point")], "No"), "Default_Ignorable_Code_Point")
# --- Vertical_Orientation (UAX #50): U upright, R rotated, Tu/Tr transformed with fallback upright/rotated
emit("VerticalOrientation", *partition(parse_ranges("VerticalOrientation.txt"), "R", ("U", "Tu", "Tr")), "Vertical_Orientation (UAX #50)")
# --- Script
emit("Script", *partition(parse_ranges("Scripts.txt"), "Unknown"), "Script")
# --- Bidi_Class (DerivedBidiClass lists every code point incl. unassigned)
emit("BidiClass", *partition(parse_ranges("DerivedBidiClass.txt"), "L"), "Bidi_Class")
# --- Bidi_Paired_Bracket_Type
br = []
pair = {}
for line in read("BidiBrackets.txt"):
    line = line.split("#")[0].strip()
    if not line: continue
    a, b, t = [x.strip() for x in line.split(";")]
    cp = int(a, 16); br.append((cp, cp, t)); pair[cp] = int(b, 16)
emit("BracketType", *partition(br, "n"), "Bidi_Paired_Bracket_Type (o, c, n)")
# --- Bidi_Mirroring_Glyph (as pairs) and bracket pairs
mir = {}
for line in read("BidiMirroring.txt"):
    line = line.split("#")[0].strip()
    if not line: continue
    a, b = [int(x.strip(), 16) for x in line.split(";")]
    mir[a] = b
def pairs(m): return ", ".join(f"0x{k:X} -> 0x{v:X}" for k, v in sorted(m.items()))
objs.append(f"""object BidiData:
  val mirror: Map[Int, Int] = Map({pairs(mir)})
  val bracketPair: Map[Int, Int] = Map({pairs(pair)})
""")

# --- Joining_Type
emit("Joining", *partition(parse_ranges("DerivedJoiningType.txt"), "U"), "Joining_Type (U, C, D, L, R, T)")
# --- Indic syllabic / positional category
emit("IndicSyll", *partition(parse_ranges("IndicSyllabicCategory.txt"), "Other"), "Indic_Syllabic_Category")
emit("IndicPos", *partition(parse_ranges("IndicPositionalCategory.txt"), "NA"), "Indic_Positional_Category")
# --- Canonical_Combining_Class (value names are the numeric classes)
emit("Ccc", *partition(parse_ranges("DerivedCombiningClass.txt"), "0"), "Canonical_Combining_Class; `names(i).toInt` is the class")
# --- canonical decompositions (UnicodeData field 5 without a <tag>), Hangul is algorithmic
decomp = []
for line in read("UnicodeData.txt"):
    f = line.split(";")
    if f[5] and not f[5].startswith("<"):
        parts = [int(x, 16) for x in f[5].split()]
        decomp.append((int(f[0], 16), parts[0], parts[1] if len(parts) > 1 else 0))
excl = set()
for line in read("CompositionExclusions.txt"):
    line = line.split("#")[0].strip()
    if line: excl.add(int(line, 16))
flat = []
for cp, a, b in decomp: flat += [cp, a, b]
exflat = sorted(excl)
objs.append(f"""object NormData:
  /** Canonical decompositions as (code point, first, second or 0) triples. */
  val decompositions: Array[Int] = RangeTable.decodeInts({len(flat)}, Array(
    {", ".join('"' + c + '"' for c in [enc(flat)[i:i+8000] for i in range(0, len(enc(flat)), 8000)])}))
  /** Script-specific composition exclusions (CompositionExclusions.txt). */
  val exclusions: Array[Int] = RangeTable.decodeInts({len(exflat)}, Array("{enc(exflat)}"))
""")

# --- Indic-family shaper categories and positions (port of HarfBuzz's gen-indic-table.py)
ALLOWED_SINGLES = [0x00A0, 0x25CC]
ALLOWED_BLOCKS = ['Basic Latin', 'Latin-1 Supplement', 'Devanagari', 'Bengali', 'Gurmukhi', 'Gujarati', 'Oriya', 'Tamil', 'Telugu',
    'Kannada', 'Malayalam', 'Myanmar', 'Khmer', 'Vedic Extensions', 'General Punctuation', 'Superscripts and Subscripts',
    'Devanagari Extended', 'Myanmar Extended-B', 'Myanmar Extended-A', 'Myanmar Extended-C']
syll = {}; posn = {}; block = {}
for lo, hi, v in parse_ranges("IndicSyllabicCategory.txt"):
    for u in range(lo, hi + 1): syll[u] = v
for lo, hi, v in parse_ranges("IndicPositionalCategory.txt"):
    for u in range(lo, hi + 1): posn[u] = v
for lo, hi, v in parse_ranges("Blocks.txt"):
    for u in range(lo, hi + 1): block[u] = v
combined = {}
for u in set(syll) | set(posn):
    if u in block and (block[u] in ALLOWED_BLOCKS or u in ALLOWED_SINGLES):
        combined[u] = (syll.get(u, "Other"), posn.get(u, "Not_Applicable"), block[u])
category_map = {'Other': 'X', 'Avagraha': 'Symbol', 'Bindu': 'SM', 'Brahmi_Joining_Number': 'PLACEHOLDER', 'Cantillation_Mark': 'A',
  'Consonant': 'C', 'Consonant_Dead': 'C', 'Consonant_Final': 'CM', 'Consonant_Head_Letter': 'C', 'Consonant_Initial_Postfixed': 'C',
  'Consonant_Killer': 'M', 'Consonant_Medial': 'CM', 'Consonant_Placeholder': 'PLACEHOLDER', 'Consonant_Preceding_Repha': 'Repha',
  'Consonant_Prefixed': 'X', 'Consonant_Subjoined': 'CM', 'Consonant_Succeeding_Repha': 'CM', 'Consonant_With_Stacker': 'CS',
  'Gemination_Mark': 'SM', 'Invisible_Stacker': 'H', 'Joiner': 'ZWJ', 'Modifying_Letter': 'X', 'Non_Joiner': 'ZWNJ', 'Nukta': 'N',
  'Number': 'PLACEHOLDER', 'Number_Joiner': 'PLACEHOLDER', 'Pure_Killer': 'M', 'Register_Shifter': 'RS', 'Syllable_Modifier': 'SM',
  'Tone_Letter': 'X', 'Tone_Mark': 'N', 'Virama': 'H', 'Visarga': 'SM', 'Vowel': 'V', 'Vowel_Dependent': 'M', 'Vowel_Independent': 'V'}
position_map = {'Not_Applicable': 'END', 'Left': 'PRE_C', 'Top': 'ABOVE_C', 'Bottom': 'BELOW_C', 'Right': 'POST_C',
  'Bottom_And_Right': 'POST_C', 'Left_And_Right': 'POST_C', 'Top_And_Bottom': 'BELOW_C', 'Top_And_Bottom_And_Left': 'BELOW_C',
  'Top_And_Bottom_And_Right': 'POST_C', 'Top_And_Left': 'ABOVE_C', 'Top_And_Left_And_Right': 'POST_C', 'Top_And_Right': 'POST_C',
  'Overstruck': 'AFTER_MAIN', 'Visual_order_left': 'PRE_M'}
category_overrides = {}
for u in range(0xFE00, 0xFE10): category_overrides[u] = 'VS'
for u in (0x2015, 0x2022, 0x25FB, 0x25FC, 0x25FD, 0x25FE): category_overrides[u] = 'PLACEHOLDER'
for u in (0x0930, 0x09B0, 0x09F0, 0x0A30, 0x0AB0, 0x0B30, 0x0BB0, 0x0C30, 0x0CB0, 0x0D30): category_overrides[u] = 'Ra'
for u in (0x0953, 0x0954, 0x11301, 0x11302, 0x11303): category_overrides[u] = 'SM'
category_overrides[0x0A40] = 'MPst'
for u in (0x0A72, 0x0A73): category_overrides[u] = 'C'
for u in list(range(0x1CE2, 0x1CE9)) + [0x1CED]: category_overrides[u] = 'A'
for u in list(range(0xA8F2, 0xA8F8)) + [0x1CE9, 0x1CEA, 0x1CEB, 0x1CEC, 0x1CEE, 0x1CEF, 0x1CF0, 0x1CF1]: category_overrides[u] = 'Symbol'
category_overrides[0x0A51] = 'M'
for u in (0x1133B, 0x1133C, 0x0AFB, 0x0B55): category_overrides[u] = 'N'
for u in (0x09FC, 0x0C80, 0x0D04): category_overrides[u] = 'PLACEHOLDER'
category_overrides[0x25CC] = 'DOTTEDCIRCLE'
category_overrides[0x179A] = 'Ra'
for u in (0x17CC, 0x17C9, 0x17CA): category_overrides[u] = 'Robatic'
for u in (0x17C6, 0x17CB, 0x17CD, 0x17CE, 0x17CF, 0x17D0, 0x17D1): category_overrides[u] = 'Xgroup'
for u in (0x17C7, 0x17C8, 0x17DD, 0x17D3): category_overrides[u] = 'Ygroup'
category_overrides[0x17D9] = 'PLACEHOLDER'
category_overrides[0x104E] = 'C'
for u in (0x1004, 0x101B, 0x105A): category_overrides[u] = 'Ra'
for u in (0x1032, 0x1036): category_overrides[u] = 'A'
category_overrides[0x103A] = 'As'
category_overrides.update({0x103E: 'MH', 0x1060: 'ML', 0x103C: 'MR', 0x103D: 'MW', 0x1082: 'MW', 0x103B: 'MY', 0x105E: 'MY', 0x105F: 'MY'})
for u in (0x1063, 0x1064, 0x1069, 0x106A, 0x106B, 0x106C, 0x106D, 0xAA7B): category_overrides[u] = 'PT'
for u in (0x1038, 0x1087, 0x1088, 0x1089, 0x108A, 0x108B, 0x108C, 0x108D, 0x108F, 0x109A, 0x109B, 0x109C): category_overrides[u] = 'SM'
category_overrides[0x104A] = 'PLACEHOLDER'
position_overrides = {0x0A51: 'BELOW_C', 0x0B01: 'BEFORE_SUB'}

def matra_right(u, b):
    return {'Devanagari': 'AFTER_SUB', 'Bengali': 'AFTER_POST', 'Gurmukhi': 'AFTER_POST', 'Gujarati': 'AFTER_POST', 'Oriya': 'AFTER_POST',
            'Tamil': 'AFTER_POST', 'Malayalam': 'AFTER_POST'}.get(b) or (('BEFORE_SUB' if u <= 0x0C42 else 'AFTER_SUB') if b == 'Telugu' else
            ('BEFORE_SUB' if u < 0x0CC3 or u > 0x0CD6 else 'AFTER_SUB') if b == 'Kannada' else 'AFTER_SUB')
def matra_top(u, b):
    return {'Devanagari': 'AFTER_SUB', 'Gurmukhi': 'AFTER_POST', 'Gujarati': 'AFTER_SUB', 'Oriya': 'AFTER_MAIN', 'Tamil': 'AFTER_SUB',
            'Telugu': 'BEFORE_SUB', 'Kannada': 'BEFORE_SUB'}.get(b, 'AFTER_SUB')
def matra_bottom(u, b):
    return {'Devanagari': 'AFTER_SUB', 'Bengali': 'AFTER_SUB', 'Gurmukhi': 'AFTER_POST', 'Gujarati': 'AFTER_POST', 'Oriya': 'AFTER_SUB',
            'Tamil': 'AFTER_POST', 'Telugu': 'BEFORE_SUB', 'Kannada': 'BEFORE_SUB', 'Malayalam': 'AFTER_POST'}.get(b, 'AFTER_SUB')
def matra_position(u, pos, b):
    return {'PRE_C': 'PRE_M', 'POST_C': matra_right(u, b), 'ABOVE_C': matra_top(u, b), 'BELOW_C': matra_bottom(u, b)}[pos]
def pos_to_cat(pos): return {'PRE_C': 'VPre', 'ABOVE_C': 'VAbv', 'BELOW_C': 'VBlw', 'POST_C': 'VPst'}[pos]

defaults = ('X', 'END', 'No_Block')
ind = {}
for k, (cat, pos, blk) in combined.items():
    cat = category_map[cat]
    if cat == 'SM' and pos == 'Not_Applicable': cat = 'SMPst'
    ind[k] = (cat, position_map[pos], blk)
for k, new_cat in category_overrides.items():
    cat, pos, _ = ind.get(k, defaults)
    ind[k] = (new_cat, pos, block.get(k, 'No_Block'))
for k, (cat, pos, blk) in list(ind.items()):
    if cat not in ('CM', 'SM', 'RS', 'H', 'M', 'MPst'): ind[k] = (cat, 'END', blk)
for k, (cat, pos, blk) in list(ind.items()):
    if cat in ('C', 'CS', 'Ra', 'CM', 'V', 'PLACEHOLDER', 'DOTTEDCIRCLE'): pos = 'BASE_C'
    elif cat in ('M', 'MPst'):
        if blk.startswith('Khmer') or blk.startswith('Myanmar'): cat = pos_to_cat(pos)
        else: pos = matra_position(k, pos, blk)
    elif cat in ('SM', 'SMPst', 'VD', 'A', 'Symbol'): pos = 'SMVD'
    ind[k] = (cat, pos, blk)
for k, new_pos in position_overrides.items():
    cat, pos, blk = ind.get(k, defaults)
    ind[k] = (cat, new_pos, blk)
ALL_CATS = ['X', 'C', 'V', 'N', 'H', 'ZWNJ', 'ZWJ', 'M', 'SM', 'A', 'VD', 'PLACEHOLDER', 'DOTTEDCIRCLE', 'RS', 'MPst', 'Repha', 'Ra', 'CM', 'Symbol', 'CS', 'SMPst',
            'VAbv', 'VBlw', 'VPre', 'VPst', 'Robatic', 'Xgroup', 'Ygroup', 'IV', 'As', 'DB', 'GB', 'MH', 'MR', 'MW', 'MY', 'PT', 'VS', 'ML']
emit("ShaperCat", *partition([(k, k, v[0]) for k, v in ind.items()], "X", ALL_CATS), "Indic/Khmer/Myanmar shaper category (HarfBuzz tables)")
emit("ShaperPos", *partition([(k, k, v[1]) for k, v in ind.items()], "END"), "Indic shaper position")

# --- Universal Shaping Engine categories (port of HarfBuzz's gen-use-table.py; the *-Additional.txt overrides are
#     Microsoft's, MIT licensed, vendored in tools/ms-use)
MS = os.path.join(os.path.dirname(__file__), "ms-use")
def read_ranges_file(path, field=1):
    out = {}
    for line in open(path, encoding="utf-8").read().splitlines():
        line = line.split("#")[0].strip()
        if not line: continue
        parts = [x.strip() for x in line.split(";")]
        r = parts[0].split("..")
        lo = int(r[0], 16); hi = int(r[-1], 16)
        for u in range(lo, hi + 1): out[u] = parts[field]
    return out
u_isc = {u: v for lo, hi, v in parse_ranges("IndicSyllabicCategory.txt") for u in range(lo, hi + 1)}
for u, v in read_ranges_file(os.path.join(MS, "IndicSyllabicCategory-Additional.txt")).items():
    u_isc[u] = 'Syllable_Modifier' if v == 'Consonant_Final_Modifier' else v
u_ipc = {u: v for lo, hi, v in parse_ranges("IndicPositionalCategory.txt") for u in range(lo, hi + 1)}
for u, v in read_ranges_file(os.path.join(MS, "IndicPositionalCategory-Additional.txt")).items():
    u_ipc[u] = 'Not_Applicable' if v == 'NA' else v
u_jt = {u: 'jt_' + v for lo, hi, v in parse_ranges("DerivedJoiningType.txt") for u in range(lo, hi + 1)}
u_di = {u for lo, hi, v in parse_ranges("DerivedCoreProperties.txt", 1, lambda p: len(p) == 2 and p[1] == "Default_Ignorable_Code_Point") for u in range(lo, hi + 1)}
u_gc = {u: v for lo, hi, v in gc for u in range(lo, hi + 1)}
u_blk = {u: v for lo, hi, v in parse_ranges("Blocks.txt") for u in range(lo, hi + 1)}
u_scr = {u: v for lo, hi, v in parse_ranges("Scripts.txt") for u in range(lo, hi + 1)}
DISABLED = {'Arabic', 'Lao', 'Samaritan', 'Syriac', 'Thai'}

def use_category(U, ISC, IPC, AJT, DI, GC):
    isc_in = lambda *x: ISC in x
    base_other = lambda: ISC == 'Consonant_Placeholder' or U in (0x2015, 0x2022, 0x25FB, 0x25FC, 0x25FD, 0x25FE)
    def is_BASE():
        return (isc_in('Number', 'Consonant', 'Consonant_Head_Letter', 'Tone_Letter', 'Vowel_Independent') or
                (AJT in ('jt_C', 'jt_D', 'jt_L', 'jt_R') and ISC != 'Joiner') or
                (GC == 'Lo' and isc_in('Avagraha', 'Bindu', 'Consonant_Final', 'Consonant_Medial', 'Consonant_Subjoined', 'Vowel', 'Vowel_Dependent')))
    def is_CGJ(): return ISC == 'Joiner' or (DI and GC in ('Mc', 'Me', 'Mn'))
    def is_SYM_MOD(): return ISC == 'Symbol_Modifier'
    def is_WJ():
        return (DI and U not in (0x115F, 0x1160, 0x3164, 0xFFA0, 0x1BCA0, 0x1BCA1, 0x1BCA2, 0x1BCA3) and ISC == 'Other' and not is_CGJ()) or GC == 'Cn'
    def is_SAKOT(): return U == 0x1A60
    def is_HVM(): return U == 0x0DCA
    def is_OTHER():
        return ((GC == 'Po' or isc_in('Consonant_Dead', 'Joiner', 'Modifying_Letter', 'Other')) and not is_BASE() and not base_other()
                and not is_CGJ() and not is_SYM_MOD() and not is_WJ())
    tests = [
        ('B', is_BASE), ('N', lambda: ISC == 'Brahmi_Joining_Number'), ('GB', base_other), ('CGJ', is_CGJ),
        ('F', lambda: (ISC == 'Consonant_Final' and GC != 'Lo') or ISC == 'Consonant_Succeeding_Repha'),
        ('FM', lambda: ISC == 'Syllable_Modifier'),
        ('M', lambda: (ISC == 'Consonant_Medial' and GC != 'Lo') or ISC == 'Consonant_Initial_Postfixed'),
        ('CM', lambda: isc_in('Nukta', 'Gemination_Mark', 'Consonant_Killer')),
        ('SUB', lambda: ISC == 'Consonant_Subjoined' and GC != 'Lo'), ('CS', lambda: ISC == 'Consonant_With_Stacker'),
        ('H', lambda: ISC == 'Virama' and not is_HVM()), ('HVM', is_HVM), ('HN', lambda: ISC == 'Number_Joiner'),
        ('IS', lambda: ISC == 'Invisible_Stacker' and not is_SAKOT()), ('G', lambda: ISC == 'Hieroglyph'),
        ('HM', lambda: ISC == 'Hieroglyph_Modifier'), ('HR', lambda: ISC == 'Hieroglyph_Mirror'), ('J', lambda: ISC == 'Hieroglyph_Joiner'),
        ('SB', lambda: isc_in('Hieroglyph_Mark_Begin', 'Hieroglyph_Segment_Begin')), ('SE', lambda: isc_in('Hieroglyph_Mark_End', 'Hieroglyph_Segment_End')),
        ('ZWNJ', lambda: ISC == 'Non_Joiner'), ('O', is_OTHER), ('RK', lambda: ISC == 'Reordering_Killer'),
        ('R', lambda: isc_in('Consonant_Preceding_Repha', 'Consonant_Prefixed')), ('Sk', is_SAKOT), ('SM', is_SYM_MOD),
        ('V', lambda: ISC == 'Pure_Killer' or (GC != 'Lo' and isc_in('Vowel', 'Vowel_Dependent'))),
        ('VM', lambda: isc_in('Tone_Mark', 'Cantillation_Mark', 'Register_Shifter', 'Visarga') or (GC != 'Lo' and ISC == 'Bindu')),
        ('WJ', is_WJ),
    ]
    hits = [k for k, f in tests if f()]
    assert len(hits) == 1, (hex(U), ISC, IPC, AJT, DI, GC, hits)
    return hits[0]

use_positions = {
    'F': {'Abv': ['Top'], 'Blw': ['Bottom'], 'Pst': ['Right']},
    'M': {'Abv': ['Top'], 'Blw': ['Bottom', 'Bottom_And_Left', 'Bottom_And_Right'], 'Pst': ['Right'], 'Pre': ['Left', 'Top_And_Bottom_And_Left']},
    'CM': {'Abv': ['Top'], 'Blw': ['Bottom', 'Overstruck']},
    'V': {'Abv': ['Top', 'Top_And_Bottom', 'Top_And_Bottom_And_Right', 'Top_And_Right'], 'Blw': ['Bottom', 'Overstruck', 'Bottom_And_Right'],
          'Pst': ['Right'], 'Pre': ['Left', 'Top_And_Left', 'Top_And_Left_And_Right', 'Left_And_Right']},
    'VM': {'Abv': ['Top'], 'Blw': ['Bottom', 'Overstruck'], 'Pst': ['Right'], 'Pre': ['Left']},
    'SM': {'Abv': ['Top'], 'Blw': ['Bottom']},
    'FM': {'Abv': ['Top'], 'Blw': ['Bottom'], 'Pst': ['Not_Applicable']},
}
use_ranges = []
cps = set(u_isc) | set(u_ipc) | set(u_jt) | u_di
for U in sorted(cps):
    if u_scr.get(U, 'Unknown') in DISABLED: continue
    ISC = u_isc.get(U, 'Other'); IPC = u_ipc.get(U, 'Not_Applicable'); AJT = u_jt.get(U, 'jt_X')
    GC = u_gc.get(U, 'Cn')
    if 0x1CE2 <= U <= 0x1CE8: ISC = 'Cantillation_Mark'
    if 0x0F18 <= U <= 0x0F19 or 0x0F3E <= U <= 0x0F3F: ISC = 'Vowel_Dependent'
    if U == 0x1CED: ISC = 'Tone_Mark'
    cat = use_category(U, ISC, IPC, AJT, U in u_di, GC)
    if U in (0x11302, 0x11303, 0x114C1): IPC = 'Top'
    if cat in use_positions:
        m = [k for k, v in use_positions[cat].items() if IPC in v]
        if len(m) != 1:
            assert IPC in ('Not_Applicable', 'Visual_Order_Left') or U == 0x0F7F, (hex(U), cat, IPC)
            if IPC in ('Not_Applicable', 'Visual_Order_Left') or U == 0x0F7F: m = [None]
        if m[0]: cat += m[0]
    use_ranges.append((U, U, cat))
USE_ALL = ['O', 'B', 'N', 'GB', 'CGJ', 'SUB', 'H', 'HN', 'ZWNJ', 'WJ', 'R', 'CS', 'IS', 'Sk', 'G', 'J', 'SB', 'SE', 'HVM', 'HM', 'HR', 'RK',
    'FAbv', 'FBlw', 'FPst', 'MAbv', 'MBlw', 'MPst', 'MPre', 'CMAbv', 'CMBlw', 'VAbv', 'VBlw', 'VPst', 'VPre', 'VMAbv', 'VMBlw', 'VMPst', 'VMPre',
    'SMAbv', 'SMBlw', 'FMAbv', 'FMBlw', 'FMPst']
emit("UseCat", *partition(use_ranges, "O", USE_ALL), "Universal Shaping Engine category (HarfBuzz tables)")

# --- ISO 15924 codes of scripts (sc aliases: `sc ; Latn ; Latin`)
codes = {}
for line in read("PropertyValueAliases.txt"):
    if line.startswith("sc ;"):
        f = [x.strip() for x in line.split("#")[0].split(";")]
        codes[f[2]] = f[1]
objs.append("object ScriptCodes:\n  val codes: Map[String, String] = Map(" + ", ".join(f'"{k}" -> "{v}"' for k, v in sorted(codes.items())) + ")\n")

hdr = "// GENERATED by tools/gen_unicode.py from the Unicode Character Database - do not edit.\npackage scalaui.unicode\n\n"
open(out, "w").write(hdr + "\n".join(objs))
print("wrote", out, os.path.getsize(out))
