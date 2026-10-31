---
title: unicode
section: Modules
order: 6
description: Unicode bidi, grapheme and word segmentation, line breaking, scripts and normalization.
---
# unicode

`scalaui.unicode` implements the Unicode algorithms the text stack needs. No Scala Native library provides them, so they are implemented here from generated tables (`tools/gen_unicode.py` reads the Unicode Character Database) and checked against the official conformance files (sampled copies are in `src/test/resources/ucd`). No dependencies.

```scala
import scalaui.unicode.*
```

## CodePoints

Most algorithms work on code point arrays. `CodePoints(s)` converts a string and remembers the UTF-16 offset of each code point:

```scala
val cp = CodePoints("a😀b")
cp.cps       // Array(97, 0x1F600, 98)
cp.offset(2) // 3: UTF-16 index of the third code point
cp.length    // string length in UTF-16 units
```

## Algorithms

| Object | UAX | API |
|---|---|---|
| `Bidi` | #9 | `resolve(cps, direction = Bidi.Auto): Bidi.Result` (paragraph level, per-character `levels`, `removed`), `reorder(levels, offset)` for visual order, `mirror(cp)`; directions `Bidi.Ltr`, `Bidi.Rtl`, `Bidi.Auto` |
| `Graphemes` | #29 | `boundaries(cp)`, `offsets(s)`, `next(s, i)`, `prev(s, i)` over UTF-16 indices |
| `LineBreaks` | #14 | `opportunities(cp): Array[Byte]` with `NoBreak`, `Allowed`, `Mandatory`; `offsets(s)`, `mandatory(s)` |
| `Scripts` | #24 | `of(cp): String` (script name), `isoCode(name)`, `itemize(cps): Array[Run]` for script runs |
| `Normalizer` | #15 | `nfc`, `nfd` on strings or code point arrays; `combiningClass(cp)`, `composePair(a, b)`, `decomposeOnce(cp)` |
| `Gc`, `Eaw`, `Lb` | | property tables (general category, East Asian width, line break class) as `RangeTable`s |

The implementation follows UAX #14 revision 56 and Unicode 18 data.

## Examples

```scala
import scalaui.unicode.*

// caret movement that respects grapheme clusters
val s = "é😀"                       // e + combining acute, then an emoji
val next = Graphemes.next(s, 0)           // 2: the accent stays with the e

// line breaking
val breaks = LineBreaks.opportunities(CodePoints("Hello world"))
// breaks(6) == LineBreaks.Allowed

// bidi
val r = Bidi.resolve(CodePoints("abc אבג").cps)
val visual = Bidi.reorder(r.levels)

// normalization
assert(Normalizer.nfc("é") == "é")
```
