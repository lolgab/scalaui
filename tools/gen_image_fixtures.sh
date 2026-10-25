#!/usr/bin/env bash
# Regenerates the image codec fixtures with ImageMagick / libwebp / ffmpeg (development tools only).
# Every fixture X.ext gets X.ref.png: the same pixels decoded by the reference tool, which the tests compare against.
set -euo pipefail
cd "$(dirname "$0")/../modules/codecs/src/test/resources"

base=/tmp/scalaui-fixture-base.png
# a 64x48 test picture with smooth gradients, hard edges, text and a translucent disc
magick -size 64x48 gradient:'#ff3030-#3030ff' \
  -fill '#20c020' -draw 'rectangle 6,6 30,22' -fill white -draw 'line 0,40 63,10' \
  -fill 'rgba(255,255,0,0.5)' -draw 'circle 44,28 44,44' -fill black -draw 'rectangle 8,34 12,44' -draw 'line 14,44 20,34' -draw 'line 20,34 26,44' PNG32:$base
# opaque variant
magick $base -background '#808080' -alpha remove -alpha off PNG24:/tmp/scalaui-fixture-opaque.png
# few colours, for palette formats
magick /tmp/scalaui-fixture-opaque.png -colors 16 -type Palette PNG8:/tmp/scalaui-fixture-16.png

ref() { magick "$1" PNG32:"${1%.*}.ref.png"; }

# --- BMP ---
magick /tmp/scalaui-fixture-opaque.png BMP3:bmp/rgb24.bmp
magick $base -define bmp:format=bmp4 BMP:bmp/bgra32.bmp
magick /tmp/scalaui-fixture-opaque.png -define bmp:subtype=RGB555 BMP:bmp/rgb555.bmp
magick /tmp/scalaui-fixture-opaque.png -define bmp:subtype=RGB565 BMP:bmp/rgb565.bmp
magick /tmp/scalaui-fixture-16.png -colors 16 BMP3:bmp/pal4.bmp
magick /tmp/scalaui-fixture-opaque.png -colors 200 -type Palette BMP3:bmp/pal8.bmp
magick /tmp/scalaui-fixture-opaque.png -monochrome BMP3:bmp/mono1.bmp
magick /tmp/scalaui-fixture-opaque.png -colors 200 -type Palette -compress RLE BMP3:bmp/rle8.bmp
magick /tmp/scalaui-fixture-16.png -colors 16 -compress RLE BMP3:bmp/rle4.bmp
magick /tmp/scalaui-fixture-opaque.png -flip BMP3:bmp/topdown.bmp   # same pixels as flipped data (reference is the flip)
for f in bmp/*.bmp; do ref "$f"; done
# a top-down bitmap: negative height
python3 - <<'PY'
import struct
d = bytearray(open('bmp/rgb24.bmp','rb').read())
w, h = struct.unpack_from('<ii', d, 18)
off = struct.unpack_from('<I', d, 10)[0]
rowsize = (w*3 + 3)//4*4
rows = [d[off+i*rowsize: off+(i+1)*rowsize] for i in range(abs(h))]
d[off:] = b''.join(reversed(rows))
struct.pack_into('<i', d, 22, -h)
open('bmp/rgb24_topdown.bmp','wb').write(d)
PY
cp bmp/rgb24.ref.png bmp/rgb24_topdown.ref.png
rm -f bmp/topdown.bmp bmp/topdown.ref.png

# --- GIF ---
magick /tmp/scalaui-fixture-16.png GIF:gif/static.gif
magick -size 64x48 xc:none -fill '#20c020' -draw 'circle 32,24 32,40' -fill '#c02020' -draw 'rectangle 4,4 20,14' GIF:gif/transparent.gif
magick /tmp/scalaui-fixture-16.png -interlace GIF GIF:gif/interlaced.gif
for f in gif/static.gif gif/transparent.gif gif/interlaced.gif; do ref "$f"; done
# animation: 4 frames, different delays and disposal; reference frames as PNGs
magick -delay 10 -size 64x48 xc:'#304080' -fill red -draw 'circle 10,24 10,30' \
  -delay 20 \( -size 64x48 xc:'#304080' -fill red -draw 'circle 26,24 26,30' \) \
  -delay 30 \( -size 64x48 xc:'#304080' -fill red -draw 'circle 42,24 42,30' \) \
  -delay 40 \( -size 64x48 xc:'#304080' -fill red -draw 'circle 56,24 56,30' \) -loop 0 -layers optimize GIF:gif/anim.gif
magick gif/anim.gif -coalesce PNG32:gif/anim-%d.ref.png

# --- TIFF ---
magick $base -compress None TIFF:tiff/rgba_none.tif
magick /tmp/scalaui-fixture-opaque.png -compress None TIFF:tiff/rgb_none.tif
magick /tmp/scalaui-fixture-opaque.png -compress LZW TIFF:tiff/rgb_lzw.tif
magick /tmp/scalaui-fixture-opaque.png -compress Zip TIFF:tiff/rgb_zip.tif
magick /tmp/scalaui-fixture-opaque.png -compress RLE TIFF:tiff/rgb_packbits.tif
magick $base -compress LZW -define tiff:predictor=2 TIFF:tiff/rgba_lzw_pred.tif
magick /tmp/scalaui-fixture-opaque.png -colorspace Gray -compress None TIFF:tiff/gray.tif
magick /tmp/scalaui-fixture-16.png -type Palette -compress None TIFF:tiff/palette.tif
magick /tmp/scalaui-fixture-opaque.png -depth 16 -compress None TIFF:tiff/rgb16.tif
magick /tmp/scalaui-fixture-opaque.png -endian MSB -compress None TIFF:tiff/rgb_be.tif
magick /tmp/scalaui-fixture-opaque.png -define tiff:rows-per-strip=5 -compress LZW TIFF:tiff/rgb_strips.tif
magick /tmp/scalaui-fixture-opaque.png -define tiff:tile-geometry=32x32 -compress None TIFF:tiff/rgb_tiled.tif
for f in tiff/*.tif; do ref "$f"; done

# --- WebP ---
cwebp -quiet -lossless -exact $base -o webp/lossless_rgba.webp
cwebp -quiet -lossless /tmp/scalaui-fixture-opaque.png -o webp/lossless_rgb.webp
cwebp -quiet -lossless -z 9 /tmp/scalaui-fixture-16.png -o webp/lossless_pal.webp
cwebp -quiet -q 80 /tmp/scalaui-fixture-opaque.png -o webp/lossy_q80.webp
cwebp -quiet -q 30 -sns 100 -f 60 -sharpness 3 /tmp/scalaui-fixture-opaque.png -o webp/lossy_q30.webp
cwebp -quiet -q 75 -alpha_q 100 $base -o webp/lossy_alpha.webp
cwebp -quiet -q 90 -segments 4 -partition_limit 50 /tmp/scalaui-fixture-opaque.png -o webp/lossy_partitions.webp
# larger textured pictures exercise colour cache, meta prefix codes and every transform (all effort levels)
magick -seed 11 -size 192x144 plasma:fractal -blur 0x1.2 -fill 'rgba(255,255,255,0.6)' -draw 'circle 90,70 90,110' PNG32:/tmp/scalaui-fixture-big.png
for z in 0 3 6 9; do cwebp -quiet -lossless -z $z -exact /tmp/scalaui-fixture-big.png -o webp/lossless_big_z$z.webp; done
cwebp -quiet -lossless -z 6 -near_lossless 40 /tmp/scalaui-fixture-big.png -o webp/lossless_big_nl.webp
cwebp -quiet -q 85 -m 6 /tmp/scalaui-fixture-big.png -o webp/lossy_big_q85.webp
cwebp -quiet -q 20 -f 100 -sns 100 -segments 4 /tmp/scalaui-fixture-big.png -o webp/lossy_big_q20.webp
cwebp -quiet -q 95 -sharp_yuv /tmp/scalaui-fixture-big.png -o webp/lossy_big_q95.webp
cwebp -quiet -q 60 -nostrong /tmp/scalaui-fixture-big.png -o webp/lossy_big_simplefilter.webp -f 40 -strong 0 2>/dev/null || true
# animations: lossless (compared to the GIF's coalesced frames) and lossy (frame count / timing only)
gif2webp -q 100 -m 6 gif/anim.gif -o webp/anim_lossless.webp -quiet
gif2webp -lossy -q 90 gif/anim.gif -o webp/anim_lossy.webp -quiet
for f in webp/*.webp; do case "$f" in webp/anim_*) continue;; esac; dwebp -nodither -pam "$f" -o "${f%.*}.pam" -quiet; magick "${f%.*}.pam" PNG32:"${f%.*}.ref.png"; rm "${f%.*}.pam"; done
echo done
