#!/bin/bash
# Validates and renders the PDFs written by PdfSuite (default target/pdf-out): qpdf --check, poppler and mupdf renders (compared with each other
# and with <name>.ref.png when present), and pdftotext extraction. usage: tools/pdf_check.sh [dir] [fuzz percent]
dir="${1:-target/pdf-out}"; fuzz="${2:-12}"
mkdir -p "$dir/render"
for f in "$dir"/*.pdf; do
  n=$(basename "$f" .pdf)
  qpdf --check "$f" > "$dir/render/$n.qpdf.txt" 2>&1 && q=ok || q=FAIL
  pdftoppm -r 72 -png "$f" "$dir/render/$n.poppler" 2>/dev/null
  mutool draw -q -r 72 -o "$dir/render/$n.mupdf-%d.png" "$f" 2>/dev/null
  pdftotext -layout "$f" "$dir/render/$n.txt" 2>/dev/null
  line="$n: qpdf=$q"
  for p in "$dir"/render/$n.poppler-*.png; do
    k=$(basename "$p" | sed 's/.*poppler-0*//; s/.png//')
    m="$dir/render/$n.mupdf-$k.png"
    if [ -f "$m" ]; then
      d=$(magick compare -metric AE -fuzz ${fuzz}% "$p" "$m" null: 2>&1 | awk '{print $1}')
      line="$line p$k:poppler-vs-mupdf=${d}px"
    fi
    if [ -f "$dir/$n.ref.png" ] && [ "$k" = "1" ]; then
      d=$(magick compare -metric AE -fuzz ${fuzz}% "$p" "$dir/$n.ref.png" null: 2>&1 | awk '{print $1}')
      line="$line vs-ref=${d}px"
    fi
  done
  echo "$line"
done
