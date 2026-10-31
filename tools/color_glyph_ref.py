#!/usr/bin/env python3
"""Reference renders of colour glyphs for the ColorGlyphSuite (imaging module): COLR v0/v1 with BlackRenderer's Skia backend, sbix/CBDT with
Pillow. Every glyph with colour data is drawn at SIZE px per em on a 160x160 canvas with its origin at (20, 120), the framing ColorGlyphSuite uses.
usage: color_glyph_ref.py <fonts dir> <out dir> [size]   (pip install fonttools blackrenderer skia-python pillow)"""
import io, os, sys
from fontTools.ttLib import TTFont
from PIL import Image

fonts_dir, out, size = sys.argv[1], sys.argv[2], float(sys.argv[3]) if len(sys.argv) > 3 else 100.0
os.makedirs(out, exist_ok=True)
W = H = 160; OX, OY = 20, 120

def colr_gids(tt):
    names = set()
    if not hasattr(tt['COLR'], 'table'): return sorted(tt.getGlyphID(n) for n in tt['COLR'].ColorLayers)
    colr = tt['COLR'].table
    if colr.BaseGlyphRecordArray: names |= {r.BaseGlyph for r in colr.BaseGlyphRecordArray.BaseGlyphRecord}
    if colr.BaseGlyphList: names |= {r.BaseGlyph for r in colr.BaseGlyphList.BaseGlyphPaintRecord}
    return sorted(tt.getGlyphID(n) for n in names)

for fn in sorted(os.listdir(fonts_dir)):
    if not fn.endswith('.ttf'): continue
    path = os.path.join(fonts_dir, fn); tt = TTFont(path); base = fn[:-4]
    upem = tt['head'].unitsPerEm; s = size / upem
    order = tt.getGlyphOrder()
    if 'COLR' in tt:
        from blackrenderer.font import BlackRendererFont
        from blackrenderer.backends.skia import SkiaPixelSurface
        font = BlackRendererFont(path)
        for gid in colr_gids(tt):
            surface = SkiaPixelSurface()
            try:
                with surface.canvas((-OX, -(H - OY), W - OX, OY)) as canvas:
                    canvas.scale(s)
                    font.drawGlyph(order[gid], canvas, palette=font.getPalette(0))
            except RecursionError:
                continue  # intentionally cyclic test glyphs
            surface.saveImage(os.path.join(out, f'{base}-g{gid}.png'))
    elif 'sbix' in tt or 'CBDT' in tt:
        bitmaps = {}
        if 'sbix' in tt:
            strike = max(tt['sbix'].strikes.values(), key=lambda x: x.ppem)
            for name, g in strike.glyphs.items():
                if g.graphicType == 'png ' and g.imageData:
                    bitmaps[tt.getGlyphID(name)] = (g.imageData, strike.ppem, g.originOffsetX, g.originOffsetY, None)
        else:
            cblc, cbdt = tt['CBLC'], tt['CBDT']
            for st, data in zip(cblc.strikes, cbdt.strikeData):
                ppem = st.bitmapSizeTable.ppemY
                for name, g in data.items():
                    if hasattr(g, 'imageData') and g.imageData[:4] == b'\x89PNG':
                        m = g.metrics
                        bitmaps[tt.getGlyphID(name)] = (g.imageData, ppem, m.BearingX, None, m.BearingY)
        for gid, (png, ppem, bx, oy, top) in sorted(bitmaps.items()):
            img = Image.open(io.BytesIO(png)).convert('RGBA')
            k = size / ppem  # strike pixel -> output pixel
            w, h = max(1, round(img.width * k)), max(1, round(img.height * k))
            img = img.resize((w, h), Image.BOX if k < 1 else Image.BILINEAR)
            x = OX + bx * k
            y = OY - top * k if top is not None else OY - oy * k - img.height
            canvas = Image.new('RGBA', (W, H), (0, 0, 0, 0))
            canvas.alpha_composite(img, (round(x), round(y)))
            canvas.save(os.path.join(out, f'{base}-g{gid}.png'))
