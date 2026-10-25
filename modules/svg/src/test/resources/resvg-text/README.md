Text fixtures from the resvg test suite (`crates/resvg/tests/tests/text`, MIT OR Apache-2.0) that need fonts other than Noto Sans: vertical writing modes and colour fonts.
Fonts are in `../resvg-fonts`. `color-font/sbix` is left out (resvg shifts sbix bitmaps by an outline-bbox heuristic that scalaui does not copy) and so is
`glyph-orientation-vertical` (resvg ignores that property, scalaui maps it to `text-orientation` as CSS Writing Modes allows).
